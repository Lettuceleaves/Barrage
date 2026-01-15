package com.barrage.engine;

import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * 基于 io_uring 的高性能 HTTP 服务端引擎。
 * <p>
 * 架构：Thread-Per-Core + Multi-Reactor
 * 特性：Zero-Copy, Lock-Free, Zero-GC (Runtime)
 * <p>
 * 变更说明：
 * 1. 移除了 HttpMessage 依赖，使用全局共享 Segment 存储静态响应。
 * 2. 优化了 Accept/Read/Write 的事件循环逻辑。
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    private int serverFd;

    // 全局共享的响应内存块 (Off-Heap)
    // 所有 Worker 线程读取同一块内存进行发送，无需复制
    private final Arena globalArena = Arena.ofShared();
    private final MemorySegment sharedResponseSegment;

    /**
     * 构建服务端引擎。
     *
     * @param port    监听端口
     * @param threads Worker 线程数
     */
    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;

        // 初始化静态响应报文 (HTTP/1.1 200 OK)
        byte[] responseBytes = (
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 12\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Server: Barrage-Engine\r\n" +
                        "\r\n" +
                        "Hello World!"
        ).getBytes(StandardCharsets.UTF_8);

        // 将响应“固化”到堆外内存
        this.sharedResponseSegment = globalArena.allocate(responseBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(responseBytes), 0, sharedResponseSegment, 0, responseBytes.length);
    }

    public void start() throws IOException {
        // 1. 初始化监听 Socket
        NativeSocket s = new NativeSocket();

        // 关键：强制设置 SO_REUSEADDR，允许在 TIME_WAIT 状态下重启服务
        s.setReuseAddr();

        s.bind(port);
        s.listen(BasicConfig.getQUEUE_DEPTH());

        this.serverFd = s.getFd();

        System.out.println("[ServerEngine] Listening on port " + port + " (FD: " + serverFd + ")");
        System.out.println("[ServerEngine] Static Response Size: " + sharedResponseSegment.byteSize() + " bytes");

        // 2. 启动 Worker 线程
        for (int i = 0; i < threads; i++) {
            new Thread(new ServerWorker(serverFd, sharedResponseSegment), "server-worker-" + i).start();
        }
    }

    /**
     * 核心工作线程
     */
    private static class ServerWorker implements Runnable {
        private final int serverFd;
        private final MemorySegment responseData; // 引用外部传入的共享内存

        private static final int EVENT_ACCEPT = 0;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        ServerWorker(int fd, MemorySegment responseData) {
            this.serverFd = fd;
            this.responseData = responseData;
        }

        @Override
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            // 线程封闭 Arena：Zero-GC 的核心
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                // 预分配内存池 (连接上下文)
                MemoryArena memoryArena = new MemoryArena(arena, BasicConfig.getQUEUE_DEPTH() * 2);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 提交初始 Accept 请求
                int initIdx = memoryArena.allocate();
                addAccept(ring, serverFd, initIdx, memoryArena);
                ring.submit();

                // Core Loop
                while (true) {
                    int cqeCount = 0;

                    // Batch Processing: 批量处理 CQE 以减少系统调用开销
                    while (cqeCount < BasicConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }

                    if (cqeCount > 0) {
                        // 有任务处理过，立即提交新任务
                        ring.submitAndWait(0);
                    } else {
                        // 无任务，阻塞等待至少 1 个事件 (防空转)
                        ring.submitAndWait(1);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Worker Error] " + Thread.currentThread().getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // 1. 处理 IO 错误
            if (cqe.res < 0) {
                if (type == EVENT_ACCEPT) {
                    // Accept 失败通常可恢复（如 ulimit 限制），重新提交
                    addAccept(ring, serverFd, idx, arena);
                } else {
                    // 读写失败（连接重置/断开），关闭资源
                    closeConnection(fd, idx, arena);
                }
                return;
            }

            // 2. 状态机流转
            switch (type) {
                case EVENT_ACCEPT -> {
                    // A. 重新提交 Accept (保持监听)
                    addAccept(ring, serverFd, idx, arena);

                    // B. 处理新连接
                    int clientFd = cqe.res;
                    int readIdx = arena.allocate();
                    if (readIdx != -1) {
                        // 注册 READ 事件
                        addRead(ring, clientFd, readIdx, arena);
                    } else {
                        // 内存池满，丢弃连接
                        new NativeSocket(clientFd).close();
                    }
                }
                case EVENT_READ -> {
                    if (cqe.res == 0) { // EOF
                        closeConnection(fd, idx, arena);
                    } else {
                        // 收到请求 -> 转换为 WRITE (发送响应)
                        addWrite(ring, fd, idx, arena);
                    }
                }
                case EVENT_WRITE -> {
                    // 发送完毕 -> 转换为 READ (Keep-Alive 等待下一个请求)
                    addRead(ring, fd, idx, arena);
                }
                default -> System.err.println("Unknown event type: " + type);
            }
        }

        private void closeConnection(int fd, int idx, MemoryArena arena) {
            new NativeSocket(fd).close();
            arena.free(idx);
        }

        private void addAccept(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return; // Should handle ring full

            // 手动填充 SQE 以获得最佳性能
            sqe.fill((byte) 0);
            sqe.set(ValueLayout.JAVA_BYTE, NativeConstants.SQE_OFF_OPCODE, NativeConstants.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_INT, NativeConstants.SQE_OFF_FD, fd);

            arena.setEventInfo(idx, EVENT_ACCEPT, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 使用 io_uring wrapper (假设已封装 prepRead)
            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);

            arena.setEventInfo(idx, EVENT_READ, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 零拷贝发送：直接使用 Shared Response Segment
            r.prepSend(sqe, fd, responseData, (int) responseData.byteSize(), 0);

            arena.setEventInfo(idx, EVENT_WRITE, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}