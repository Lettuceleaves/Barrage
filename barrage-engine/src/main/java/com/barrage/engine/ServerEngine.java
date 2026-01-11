package com.barrage.engine;

import com.barrage.kernel.config.GlobalConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 基于 io_uring 的高性能 HTTP 服务端引擎。
 * 修复了端口占用导致的 bind 98 错误，并优化了 Worker 事件循环。
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    private int serverFd;

    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;
    }

    /**
     * 启动服务端。
     * 解决了 bind fail: 98 的核心在于先调用 setReuseAddr()。
     */
    public void start() throws IOException {
        // 1. 初始化监听 Socket
        NativeSocket s = new NativeSocket();

        // 核心修复点：在 bind 之前强制夺回端口
        s.setReuseAddr();

        s.bind(port);
        // 设置足够的 Backlog 以应对百万级 QPS 的连接建立需求
        s.listen(GlobalConfig.getQUEUE_DEPTH());

        this.serverFd = s.getFd();

        System.out.println("[ServerEngine] Listening on " + port + " (FD: " + serverFd + ")");
        System.out.println("[ServerEngine] Initializing " + threads + " worker threads (Thread-Per-Core)...");

        // 2. 启动 Worker 线程
        for (int i = 0; i < threads; i++) {
            new Thread(new ServerWorker(serverFd), "server-worker-" + i).start();
        }
    }

    private static class ServerWorker implements Runnable {
        private final int serverFd;

        private static final int EVENT_ACCEPT = 0;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        ServerWorker(int fd) { this.serverFd = fd; }

        @Override
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            // 使用 Confined Arena 确保线程本地内存分配的极高性能
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(GlobalConfig.getQUEUE_DEPTH())) {

                // 内存池大小：连接数 * 2（Read/Write 槽位分离）
                MemoryArena memoryArena = new MemoryArena(arena, GlobalConfig.getQUEUE_DEPTH() * 2);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 提交初始 Accept
                int initIdx = memoryArena.allocate();
                addAccept(ring, serverFd, initIdx, memoryArena);
                ring.submit();

                // 事件主循环
                while (true) {
                    int cqeCount = 0;
                    // 批量处理：这是保持高 QPS 的关键，减少系统调用
                    while (cqeCount < GlobalConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }

                    if (cqeCount > 0) {
                        ring.submitAndWait(0);
                    } else {
                        // 无事件时阻塞等待，避免 CPU 空转
                        ring.submitAndWait(1);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Worker Error] " + Thread.currentThread().getName() + " : " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // 处理内核返回的错误
            if (cqe.res < 0) {
                if (type == EVENT_ACCEPT) {
                    addAccept(ring, serverFd, idx, arena);
                } else {
                    new NativeSocket(fd).close();
                    arena.free(idx);
                }
                return;
            }

            switch (type) {
                case EVENT_ACCEPT -> {
                    addAccept(ring, serverFd, idx, arena);
                    int clientFd = cqe.res;
                    int readIdx = arena.allocate();
                    if (readIdx != -1) {
                        addRead(ring, clientFd, readIdx, arena);
                    } else {
                        new NativeSocket(clientFd).close();
                    }
                }
                case EVENT_READ -> {
                    if (cqe.res == 0) {
                        new NativeSocket(fd).close();
                        arena.free(idx);
                    } else {
                        addWrite(ring, fd, idx, arena);
                    }
                }
                case EVENT_WRITE -> {
                    addRead(ring, fd, idx, arena);
                }
                // 修复 SpotBugs SF_SWITCH_NO_DEFAULT 警告
                default -> {
                    System.err.println("[Critical] Unknown event type: " + type);
                    // 理论上不应到达这里，可根据需要释放资源
                }
            }
        }

        private void addAccept(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 修正：直接设置 SQE 参数，避免 prepNop 可能导致的不确定性
            sqe.fill((byte) 0); // 必须清零
            sqe.set(ValueLayout.JAVA_BYTE, NativeConstants.SQE_OFF_OPCODE, NativeConstants.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_INT, NativeConstants.SQE_OFF_FD, fd);

            arena.setEventInfo(idx, EVENT_ACCEPT, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            r.prepRead(sqe, fd, arena.getBuffer(idx), GlobalConfig.getREAD_SZ(), 0);
            arena.setEventInfo(idx, EVENT_READ, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 零拷贝发送 HttpMessage 静态响应
            r.prepSend(sqe, fd, HttpMessage.RESPONSE_200.segment(), (int) HttpMessage.RESPONSE_200.length(), 0);
            arena.setEventInfo(idx, EVENT_WRITE, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}