package com.barrage.engine;

import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.barrage.kernel.config.GlobalConfig.*;

/**
 * 基于 io_uring 的高性能 HTTP 服务端引擎。
 * <p>
 * 该类负责启动服务端监听 Socket，并分发 Worker 线程处理客户端请求。
 * 采用了 <b>Thread-Per-Core</b> 架构，配合 Linux io_uring 的异步特性，
 * 实现了全链路零拷贝（Zero-Copy）和零垃圾回收（Zero-GC）的数据处理。
 *
 * <h3>核心设计：</h3>
 * <ul>
 * <li><b>Shared-FD Accept：</b> 所有 Worker 线程共享同一个监听 Socket 的文件描述符 (serverFd)。
 * 利用 io_uring 的 {@code IORING_OP_ACCEPT} 能力，多个线程可以安全地并发提交 Accept 请求，
 * 由内核负责负载均衡（解决惊群效应），最大化连接建立吞吐量。</li>
 * <li><b>Event Loop：</b> 每个 Worker 拥有独立的事件循环，无锁处理 Accept -> Read -> Write 流程。</li>
 * <li><b>Static Response：</b> 针对压测场景，使用预分配的静态堆外内存作为 HTTP 响应，避免重复内存拷贝。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    /** 服务端监听 Socket 的原始文件描述符 (Raw File Descriptor) */
    private int serverFd;

    /**
     * 构造服务端引擎。
     *
     * @param port    监听端口
     * @param threads Worker 线程数量（建议与 CPU 物理核数一致）
     */
    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;
    }

    /**
     * 启动服务端。
     * <p>
     * 流程：
     * <ol>
     * <li>创建 Native Socket，设置 SO_REUSEADDR。</li>
     * <li>绑定端口并开启 Listen。</li>
     * <li>获取原生 FD (File Descriptor)。</li>
     * <li>启动指定数量的 Worker 线程，每个线程将独立在该 FD 上轮询 Accept 事件。</li>
     * </ol>
     *
     * @throws IOException 如果端口绑定失败或 Socket 创建失败
     */
    public void start() throws IOException {
        // 1. 启动监听 Socket
        NativeSocket s = new NativeSocket();
        s.setReuseAddr();
        s.bind(port);
        s.listen(4096); // Backlog 设置
        this.serverFd = s.getFd(); // 获取 raw fd 供 worker 使用

        // s 对象本身可以丢弃/GC，但不要 close，因为 fd 需要保持打开供 Worker 使用
        // 在真实应用中，可能需要一个 shutdown hook 来关闭它
        System.out.println("[ServerEngine] Listening on " + port + " (FD: " + serverFd + ")");

        // 2. 启动 Worker 线程
        for (int i = 0; i < threads; i++) {
            new Thread(new ServerWorker(serverFd), "server-worker-" + i).start();
        }
    }

    /**
     * Server Worker: 独立处理连接生命周期的工作线程。
     * <p>
     * 状态机流转：
     * <pre>
     * [ACCEPT] --(new conn)--> [READ] --(request)--> [WRITE] --(finish)--> [READ] (Keep-Alive)
     * ^                                                                   |
     * |-------------------------------------------------------------------|
     * </pre>
     */
    private static class ServerWorker implements Runnable {
        private final int serverFd;

        // --- 事件类型常量 ---
        /** 处理新连接接入 */
        private static final int EVENT_ACCEPT = 0;
        /** 读取客户端请求数据 */
        private static final int EVENT_READ = 1;
        /** 发送 HTTP 响应数据 */
        private static final int EVENT_WRITE = 2;

        ServerWorker(int fd) { this.serverFd = fd; }

        @Override
        public void run() {
            // 使用 confined arena，确保内存分配仅限于当前线程，最大化局部性
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(QUEUE_DEPTH)) {

                MemoryArena memoryArena = new MemoryArena(arena, QUEUE_DEPTH * 2);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 初始提交一个 Accept 请求，让内核开始监听连接
                int initIdx = memoryArena.allocate();
                addAccept(ring, serverFd, initIdx, memoryArena);
                ring.submitAndWait(0);

                // 事件循环
                while (true) {
                    int cqeCount = 0;
                    // 批量获取 CQE，减少 JNI 调用开销
                    while (cqeCount < BATCH_SIZE && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }
                    // 如果有处理事件，非阻塞提交；否则阻塞等待至少一个事件
                    if (cqeCount > 0) ring.submitAndWait(0);
                    else ring.submitAndWait(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 核心事件分发处理器。
         *
         * @param ring       io_uring 实例
         * @param cqe        完成队列条目
         * @param arena      内存管理器
         */
        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // 1. 错误处理
            if (cqe.res < 0) {
                if (type == EVENT_ACCEPT) {
                    // Accept 失败（如资源耗尽），重新提交 Accept 以避免服务中断
                    addAccept(ring, serverFd, idx, arena);
                } else {
                    // 读写失败，关闭客户端连接并释放内存槽位
                    new NativeSocket(fd).close();
                    arena.free(idx);
                }
                return;
            }

            // 2. 正常流程处理
            if (type == EVENT_ACCEPT) {
                // 收到新连接：
                // A. 立即重新提交 Accept，确保持续接收后续连接（这是高并发的关键）
                addAccept(ring, serverFd, idx, arena);

                // B. 为新建立的连接 (clientFd) 准备读请求
                int clientFd = cqe.res;
                int newIdx = arena.allocate();
                addRead(ring, clientFd, newIdx, arena);

            } else if (type == EVENT_READ) {
                if (cqe.res == 0) { // EOF: 客户端断开连接
                    new NativeSocket(fd).close();
                    arena.free(idx);
                } else {
                    // 读取到数据，解析请求（此处省略解析逻辑，直接假设合法），准备写响应
                    addWrite(ring, fd, idx, arena);
                }
            } else if (type == EVENT_WRITE) {
                // 响应发送完毕，复用连接（Keep-Alive），继续等待下一个请求
                addRead(ring, fd, idx, arena);
            }
        }

        /**
         * 提交 Accept 请求 (IORING_OP_ACCEPT)。
         * <p>
         * 注意：多个 Worker 会在同一个 serverFd 上提交此请求。
         */
        private void addAccept(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe(); if(sqe==null)return;
            // 准备 SQE，opcode 设为 ACCEPT
            // 在 liburing/kernel 中，fd 参数是监听 socket 的 fd
            r.prepNop(sqe); // Reset/Init (示例简化，实际需调用 prep_accept)
            sqe.set(ValueLayout.JAVA_BYTE, NativeConstants.SQE_OFF_OPCODE, NativeConstants.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_INT, NativeConstants.SQE_OFF_FD, fd);

            arena.setEventInfo(idx, EVENT_ACCEPT, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        /**
         * 提交 Read 请求 (IORING_OP_READ)。
         */
        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe(); if(sqe==null)return;
            r.prepRead(sqe, fd, arena.getBuffer(idx), READ_SZ, 0);

            arena.setEventInfo(idx, EVENT_READ, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        /**
         * 提交 Write 请求 (IORING_OP_SEND)。
         * <p>
         * 使用 {@link HttpMessage#RESPONSE_200} 的静态内存段，实现 Zero-Copy 发送。
         */
        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe(); if(sqe==null)return;
            // 直接发送预定义的 HTTP 200 响应
            r.prepSend(sqe, fd, HttpMessage.RESPONSE_200.segment(), HttpMessage.RESPONSE_200.length(), 0);

            arena.setEventInfo(idx, EVENT_WRITE, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}