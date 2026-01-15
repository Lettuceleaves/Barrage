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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 终极修复版 N-to-N 服务端引擎 (Byte-Scan Version)
 * <p>
 * 修复核心：
 * 1. 放弃 int 强转扫描，改为逐字节匹配，彻底解决大小端/对齐造成的漏扫问题。
 * 2. 增加“幽灵 Batch”检测日志，当读取大包但只识别出 1 个请求时报警。
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    private int serverFd;

    private final Arena globalArena = Arena.ofShared();
    private final MemorySegment sharedResponseSegment;

    // 强制 16KB 读缓冲
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
        System.out.println("[ServerEngine] Force setting BasicConfig.READ_SZ to " + ROBUST_READ_SZ);
        BasicConfig.setREAD_SZ(ROBUST_READ_SZ);

        NativeSocket s = new NativeSocket();
        s.setReuseAddr();
        s.bind(port);
        s.listen(BasicConfig.getQUEUE_DEPTH());
        this.serverFd = s.getFd();

        System.out.println("[ServerEngine] Started. Using Byte-Level Scanning.");

        // 监控线程
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
                long count = REQ_IDENTIFIED.getAndSet(0);
                if (count > 0) {
                    System.out.println("[Server Internal] Processed reqs/sec: " + count);
                }
            }
        }).start();

        for (int i = 0; i < threads; i++) {
            new Thread(new ServerWorker(serverFd, sharedResponseSegment), "server-worker-" + i).start();
        }
    }

    private static class ServerWorker implements Runnable {
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
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                MemoryArena memoryArena = new MemoryArena(arena, BasicConfig.getQUEUE_DEPTH() * 2);
                IoUring.Cqe cqe = new IoUring.Cqe();

                for (int i = 0; i < 32; i++) {
                    int idx = memoryArena.allocate();
                    if (idx != -1) addAccept(ring, serverFd, idx);
                }
                ring.submit();

                while (true) {
                    int cqeCount = 0;
                    while (cqeCount < BasicConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }
                    if (cqeCount > 0) ring.submit();
                    else ring.submitAndWait(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

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

                        // 1. 扫描请求 (Byte-by-Byte)
                        int requestCount = countRequestsSafely(buffer, safeLimit);
                        REQ_IDENTIFIED.addAndGet(requestCount);

                        // [DEBUG] 如果读取了大包(>2000字节)但只识别出1个，说明扫描有问题
                        if (bytesRead > 2000 && requestCount == 1) {
                            System.err.println("[WARN] Partial Scan Detected! Read: " + bytesRead + ", Found: " + requestCount);
                        }

                        // 2. 批量回写
                        for (int i = 0; i < requestCount; i++) {
                            addWrite(ring, fd, idx);
                        }

                        // 3. 续读
                        addRead(ring, fd, idx, arena);
                    }
                }
                case EVENT_WRITE -> {}
            }
        }

        /**
         * 绝对安全的逐字节扫描
         * 不受 CPU 大小端影响，不受内存对齐影响
         */
        private int countRequestsSafely(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;

            // 扫描整个缓冲区
            for (long i = 0; i <= length - 4; i++) {
                try {
                    // 读取第一个字节
                    byte b1 = buffer.get(ValueLayout.JAVA_BYTE, i);

                    // 检查 "GET " (G=71, E=69, T=84, Space=32)
                    if (b1 == 'G') {
                        if (buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'E' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 2) == 'T' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 3) == ' ') {
                            count++;
                            i += 3; // 跳过
                            continue;
                        }
                    }

                    // 检查 "POST" (P=80, O=79, S=83, T=84)
                    if (b1 == 'P') {
                        if (buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'O' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 2) == 'S' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 3) == 'T') {
                            count++;
                            i += 3; // 跳过
                            continue;
                        }
                    }

                    // 检查 "PUT " (P=80, U=85, T=84, Space=32)
                    if (b1 == 'P') {
                        if (buffer.get(ValueLayout.JAVA_BYTE, i + 1) == 'U' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 2) == 'T' &&
                                buffer.get(ValueLayout.JAVA_BYTE, i + 3) == ' ') {
                            count++;
                            i += 3;
                            continue;
                        }
                    }

                } catch (IndexOutOfBoundsException e) {
                    break;
                }
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
                try {
                    r.submit();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                sqe = r.nextSqe();
                if (sqe == null) Thread.onSpinWait();
            }
            return sqe;
        }
    }
}