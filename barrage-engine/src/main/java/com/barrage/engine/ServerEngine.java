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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Thread.sleep;

/**
 * ServerEngine (Fixed Resource Lifecycle)
 * 修复了 Already Closed 异常，调整了 try-with-resources 嵌套顺序。
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
    public static final AtomicLong REQ_IDENTIFIED = new AtomicLong(0);

    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;
        byte[] responseBytes = (
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 12\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Server: Barrage-Final\r\n" +
                        "\r\n" +
                        "Hello World!"
        ).getBytes(StandardCharsets.UTF_8);

        this.sharedResponseSegment = globalArena.allocate(responseBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(responseBytes), 0, sharedResponseSegment, 0, responseBytes.length);
    }

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

    public void shutdown() {
        if (!running) return;
        System.out.println("[ServerEngine] Shutting down...");

        running = false;

        // 1. 关闭 Server Socket
        try { new NativeSocket(serverFd).close(); } catch (Exception e) {}

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