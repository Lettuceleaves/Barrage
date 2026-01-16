package com.barrage.engine;

import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Thread.sleep;

/**
 * Barrage 内置的高性能 HTTP 服务端引擎 (Fixed Resource Lifecycle)。
 * <p>
 * 该类实现了一个基于 {@code io_uring} 的轻量级 HTTP Server，主要用于 {@code SELF_BENCHMARK} 模式下的闭环压力测试。
 * 它移除了所有通用 Web 服务器的复杂特性（如路由、Header 解析），专注于提供极致的 {@code ECHO} 性能，
 * 以便作为测试客户端极限吞吐量的基准靶机。
 *
 * <h2>架构特性：</h2>
 * <ul>
 * <li><b>资源生命周期修复 (Lifecycle Fix)：</b> 采用了严格的 {@code try-with-resources} 嵌套结构（外层 Arena，内层 IoUring）。
 * 确保在 {@code io_uring} 关闭后，清理逻辑（{@code closeAllSockets}）依然能安全访问堆外内存中的文件描述符，
 * 彻底解决了 "MemorySegment already closed" 异常。</li>
 * <li><b>零拷贝响应 (Zero-Copy Response)：</b> HTTP 响应报文（Hello World）在启动时分配于 {@link Arena#ofShared()}
 * 的共享堆外内存中。所有 Worker 线程通过 {@code mmap} 或直接指针引用共享同一块内存，发送时无数据拷贝。</li>
 * <li><b>HTTP Pipelining 支持：</b> 内置极其激进的 HTTP 请求计数器，能够在单次 TCP {@code recv} 系统调用中
 * 识别并处理多个堆叠的 HTTP 请求，从而有效应对高并发下的 TCP 粘包场景。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 启动和关闭操作是同步的。内部 Worker 线程完全隔离。
 *
 * @author LettuceLeaves
 * @version 1.1 (Fix Already Closed)
 * @since 2026/1/6
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    private int serverFd;

    private volatile boolean running = false;
    private final List<Thread> workers = new ArrayList<>();

    private final Arena globalArena = Arena.ofShared();
    private final MemorySegment sharedResponseSegment;

    private static final int ROBUST_READ_SZ = 16 * 1024;

    /**
     * 全局请求计数器 (Internal Monitor)。
     * 用于服务端内部统计实际接收到的有效 HTTP 请求数 (QPS)，独立于客户端统计。
     */
    public static final AtomicLong REQ_IDENTIFIED = new AtomicLong(0);

    /**
     * 初始化服务端引擎。
     * <p>
     * 预分配固定的 "Hello World" HTTP 响应报文到共享堆外内存。
     *
     * @param port    监听端口
     * @param threads Worker 线程数
     */
    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;
        byte[] responseBytes = (
                """
                        HTTP/1.1 200 OK\r
                        Content-Type: text/plain\r
                        Content-Length: 12\r
                        Connection: keep-alive\r
                        Server: Barrage-Final\r
                        \r
                        Hello World!"""
        ).getBytes(StandardCharsets.UTF_8);

        this.sharedResponseSegment = globalArena.allocate(responseBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(responseBytes), 0, sharedResponseSegment, 0, responseBytes.length);
    }

    /**
     * 启动服务端。
     * <p>
     * 1. 绑定端口并开启 {@code listen}。
     * 2. 启动一个后台守护线程，每秒打印服务端的内部处理 QPS。
     * 3. 启动所有 Worker 线程开始处理 Accept/Read/Write 事件。
     *
     * @throws IOException 如果端口绑定失败或 Socket 创建失败
     */
    public void start() throws IOException {
        BasicConfig.setREAD_SZ(ROBUST_READ_SZ);
        NativeSocket s = new NativeSocket();
        s.setReuseAddr();
        s.bind(port);
        s.listen(BasicConfig.getQUEUE_DEPTH());
        this.serverFd = s.getFd();
        this.running = true;

        System.out.println("[ServerEngine] Started on port " + port);

        Thread monitor = new Thread(() -> {
            while (running) {
                try { sleep(1000); } catch (InterruptedException e) { break; }
                long count = REQ_IDENTIFIED.getAndSet(0);
                if (count > 0 && running) {
                    System.out.println("[Server Internal] Processed reqs/sec: " + count);
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new ServerWorker(serverFd, sharedResponseSegment), "server-worker-" + i);
            workers.add(t);
            t.start();
        }
    }

    /**
     * 优雅关闭服务端。
     * <p>
     * 执行顺序：
     * <ol>
     * <li>关闭 Server Socket，停止接收新连接。</li>
     * <li>中断所有 Worker 线程。</li>
     * <li>等待 Worker 线程退出 (Join)。</li>
     * <li>释放全局共享内存 Arena。</li>
     * </ol>
     */
    public void shutdown() {
        if (!running) return;
        System.out.println("[ServerEngine] Shutting down...");

        running = false;

        // 1. 关闭 Server Socket
        try { new NativeSocket(serverFd).close(); } catch (Exception _) {}

        // 2. 发送中断
        for (Thread t : workers) t.interrupt();

        // 3. 等待退出
        long deadline = System.currentTimeMillis() + 2000;
        for (Thread t : workers) {
            long timeLeft = deadline - System.currentTimeMillis();
            if (timeLeft <= 0) break;
            try { t.join(timeLeft); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        workers.clear();

        try { if (globalArena.scope().isAlive()) globalArena.close(); } catch (Exception ignored) {}
        System.out.println("[ServerEngine] Shutdown complete.");
    }

    /**
     * 服务端工作线程 (Worker)。
     * <p>
     * 核心 Event Loop，负责 Accept 新连接以及处理已建立连接的 I/O 事件。
     */
    private class ServerWorker implements Runnable {
        private final int serverFd;
        private final MemorySegment responseData;
        private static final int EVENT_ACCEPT = 0;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        ServerWorker(int fd, MemorySegment responseData) {
            this.serverFd = fd;
            this.responseData = responseData;
        }

        /**
         * 运行 Event Loop。
         * <p>
         * <b>实现细节：</b>
         * 这里采用了特殊的 {@code try-with-resources} 嵌套顺序：
         * <pre>{@code
         * try (Arena arena = Arena.ofConfined()) {       // 1. 外层：内存生命周期
         * MemoryArena memoryArena = ...;
         * try (IoUring ring = new IoUring(...)) {    // 2. 内层：io_uring 资源
         * // loop...
         * } finally {
         * closeAllSockets(memoryArena);          // 3. 安全清理
         * }
         * }
         * }</pre>
         * 这种设计确保了当 {@code ring} 关闭后，代码仍能安全地访问 {@code memoryArena} 中的数据（如 fd 数组），
         * 从而正确关闭所有客户端连接，避免了因过早释放 Arena 导致的 JVM 崩溃。
         */
        @Override
        public void run() {
            // [关键修复] 外层 try 管理 Arena，确保它在 closeAllSockets 执行时依然存活
            try (Arena arena = Arena.ofConfined()) {

                MemoryArena memoryArena = new MemoryArena(arena, BasicConfig.getQUEUE_DEPTH() * 2);

                // [关键修复] 内层 try 管理 IoUring
                try (IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {
                    IoUring.Cqe cqe = new IoUring.Cqe();

                    // 初始挂载
                    for (int i = 0; i < 32; i++) {
                        int idx = memoryArena.allocate();
                        if (idx != -1) addAccept(ring, serverFd, idx);
                    }
                    ring.submit();

                    while (running) {
                        if (Thread.currentThread().isInterrupted()) break;

                        int cqeCount = 0;
                        while (cqeCount < BasicConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                            processEvent(ring, cqe, memoryArena);
                            cqeCount++;
                        }

                        if (cqeCount > 0) {
                            ring.submit();
                        } else {
                            try {
                                ring.submitAndWait(1);
                            } catch (Exception e) {
                                if (!running) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                } finally {
                    // [关键修复] 此时内层 IoUring 已关闭，但外层 Arena 依然存活
                    // 所以这里调用 closeAllSockets 是安全的，不会报 Already Closed
                    closeAllSockets(memoryArena);
                }
            } // Arena 在这里才会被关闭
        }

        // 保持核心逻辑纯净
        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            long rawUserData = cqe.userData;
            int type = (int) (rawUserData >>> 32);
            int idx = (int) (rawUserData & 0xFFFFFFFFL);
            int fd = arena.getFd(idx);

            if (cqe.res < 0) {
                if (type == EVENT_ACCEPT) addAccept(ring, serverFd, idx);
                else closeConnection(fd, idx, arena);
                return;
            }

            switch (type) {
                case EVENT_ACCEPT -> {
                    int clientFd = cqe.res;
                    addAccept(ring, serverFd, idx);
                    int readIdx = arena.allocate();
                    if (readIdx != -1) {
                        arena.setFd(readIdx, clientFd);
                        addRead(ring, clientFd, readIdx, arena);
                    } else {
                        new NativeSocket(clientFd).close();
                    }
                }
                case EVENT_READ -> {
                    int bytesRead = cqe.res;
                    if (bytesRead == 0) {
                        closeConnection(fd, idx, arena);
                    } else {
                        MemorySegment buffer = arena.getBuffer(idx);
                        int safeLimit = (int) Math.min(bytesRead, buffer.byteSize());
                        int requestCount = countRequestsSafely(buffer, safeLimit);
                        REQ_IDENTIFIED.addAndGet(requestCount);

                        for (int i = 0; i < requestCount; i++) addWrite(ring, fd, idx);
                        addRead(ring, fd, idx, arena);
                    }
                }
                case EVENT_WRITE -> {}
            }
        }

        private void closeAllSockets(MemoryArena arena) {
            int capacity = BasicConfig.getQUEUE_DEPTH() * 2;
            for (int i = 0; i < capacity; i++) {
                try {
                    int fd = arena.getFd(i); // 这里不会再报错了
                    if (fd > 0) new NativeSocket(fd).close();
                } catch (Exception ignored) {}
            }
        }

        // --- Helpers ---

        /**
         * 快速扫描并统计 HTTP 请求个数 (Pipelining Support)。
         * <p>
         * 粗暴但高效的解析逻辑：仅检查 HTTP 方法的前几个字节 (G, P)，
         * 忽略 Headers 和 Body 的解析。这使得服务器能够以极低的 CPU 开销处理
         * TCP 粘包中的多个 HTTP 请求。
         *
         * @param buffer 数据缓冲区
         * @param length 数据有效长度
         * @return 识别到的请求数量，至少为 1 (容错策略)
         */
        private int countRequestsSafely(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;
            for (long i = 0; i <= length - 4; i++) {
                try {
                    byte b1 = buffer.get(ValueLayout.JAVA_BYTE, i);
                    if (b1 == 'G' && buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'E') { count++; i += 3; continue; }
                    if (b1 == 'P' && buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'O') { count++; i += 3; continue; }
                    if (b1 == 'P' && buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'U') { count++; i += 3; continue; }
                } catch (IndexOutOfBoundsException e) { break; }
            }
            return Math.max(count, 1);
        }

        private void closeConnection(int fd, int idx, MemoryArena arena) {
            new NativeSocket(fd).close();
            arena.free(idx);
        }

        private void addAccept(IoUring r, int fd, int idx) {
            MemorySegment sqe = getSqeStrict(r);
            sqe.fill((byte) 0);
            sqe.set(ValueLayout.JAVA_BYTE, NativeConstants.SQE_OFF_OPCODE, NativeConstants.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_INT, NativeConstants.SQE_OFF_FD, fd);
            long encodedUserData = ((long) EVENT_ACCEPT << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, encodedUserData);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = getSqeStrict(r);
            r.prepRead(sqe, fd, arena.getBuffer(idx), ROBUST_READ_SZ, 0);
            long encodedUserData = ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, encodedUserData);
        }

        private void addWrite(IoUring r, int fd, int idx) {
            MemorySegment sqe = getSqeStrict(r);
            r.prepSend(sqe, fd, responseData, (int) responseData.byteSize(), 0);
            long encodedUserData = ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, encodedUserData);
        }

        private MemorySegment getSqeStrict(IoUring r) {
            MemorySegment sqe = r.nextSqe();
            while (sqe == null) {
                try { r.submit(); } catch (IOException e) { throw new RuntimeException(e); }
                sqe = r.nextSqe();
                if (sqe == null) Thread.onSpinWait();
            }
            return sqe;
        }
    }
}