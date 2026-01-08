package com.barrage.kernel.io;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

import static com.barrage.kernel.io.NativeConstants.*;

/**
 * IoUring 真实的性能对比测试。
 * 使用 socketpair 和 4KB 负载来对比 SEND 与 SEND_ZC + SQPOLL。
 */
public class IoUringBenchmark {

    private static final int ITERATIONS = 1_000_000;
    private static final int BATCH_SIZE = 16;
    private static final int PAYLOAD_SIZE = 4096; // 4KB

    @Test
    public void runBenchmark() throws IOException {
        System.out.println("=== IoUring Realistic Performance Benchmark ===");
        System.out.println("Iterations  : " + ITERATIONS);
        System.out.println("Payload     : " + (PAYLOAD_SIZE / 1024) + " KB");
        System.out.println("Batch Size  : " + BATCH_SIZE);
        System.out.println();

        // 1. Base: No SQPOLL, No ZC
        benchmarkScenario("Base (SEND, No SQPOLL)", 0, false);

        // 2. SQPOLL
        benchmarkScenario("SQPOLL (SEND, SQPOLL)", IORING_SETUP_SQPOLL, false);

        // 3. Turbo: SQPOLL + SEND_ZC (using registered buffers)
        benchmarkScenario("Turbo (SEND_ZC, SQPOLL)", IORING_SETUP_SQPOLL, true);
    }

    private void benchmarkScenario(String name, int flags, boolean useZc) throws IOException {
        try (Arena arena = Arena.ofShared();
             IoUring ring = new IoUring(1024, flags)) {
            
            // 创建 socketpair (AF_UNIX 是 socketpair 唯一支持的 domain)
            MemorySegment sv = arena.allocate(ValueLayout.JAVA_INT, 2);
            int ret = ring.socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
            if (ret < 0) {
                System.out.println(name + ": FAILED (socketpair, ret=" + ret + ")");
                return;
            }
            int senderFd = sv.getAtIndex(ValueLayout.JAVA_INT, 0);
            int receiverFd = sv.getAtIndex(ValueLayout.JAVA_INT, 1);

            // 设置接收端为非阻塞，并启动简单的接收端线程（为了防止缓冲区满）
            ring.fcntl(receiverFd, F_SETFL, O_NONBLOCK);
            Thread receiverThread = startReceiver(ring, receiverFd, ITERATIONS * PAYLOAD_SIZE);

            MemorySegment payload = arena.allocate(PAYLOAD_SIZE);
            payload.fill((byte) 'A');

            if (useZc) {
                ring.registerBuffers(payload);
            }

            // Warm up
            runLoop(ring, senderFd, payload, 10_000, useZc);

            long start = System.nanoTime();
            runLoop(ring, senderFd, payload, ITERATIONS, useZc);
            long end = System.nanoTime();

            receiverThread.interrupt();

            long durationMs = TimeUnit.NANOSECONDS.toMillis(end - start);
            double throughput = (ITERATIONS * (double)PAYLOAD_SIZE) / (durationMs / 1000.0) / (1024 * 1024); // MB/s

            System.out.printf("%-30s: %10d ms | %10.2f MB/s%n", name, durationMs, throughput);
            
            ring.closeFd(senderFd);
            ring.closeFd(receiverFd);
        } catch (IOException e) {
            System.out.println(name + ": SKIPPED (Unsupported or Permission Denied: " + e.getMessage() + ")");
        }
    }

    private void runLoop(IoUring ring, int fd, MemorySegment buf, int total, boolean useZc) throws IOException {
        int count = 0;
        int lastLog = 0;
        while (count < total) {
            int toSubmit = Math.min(BATCH_SIZE, total - count);
            for (int i = 0; i < toSubmit; i++) {
                MemorySegment sqe = ring.nextSqe();
                if (sqe == null) {
                    ring.submitAndGet();
                    reapCompletions(ring);
                    sqe = ring.nextSqe();
                    while (sqe == null) {
                        Thread.onSpinWait();
                        reapCompletions(ring);
                        sqe = ring.nextSqe();
                    }
                }
                if (useZc) {
                    ring.prepSendZc(sqe, fd, buf, (int) buf.byteSize(), IORING_RECVSEND_FIXED_BUF, 0);
                } else {
                    ring.prepSend(sqe, fd, buf, (int) buf.byteSize(), 0);
                }
            }
            ring.submitAndGet();
            reapCompletions(ring);
            count += toSubmit;
        }
    }

    private void reapCompletions(IoUring ring) throws IOException {
        while (ring.peekComplete() != Integer.MIN_VALUE) {
            // Reap
        }
    }

    private Thread startReceiver(IoUring ring, int fd, long totalBytes) {
        Thread t = new Thread(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(128 * 1024); // Large buffer
                long received = 0;
                while (!Thread.currentThread().isInterrupted() && received < totalBytes) {
                    int ret = readRaw(fd, buf);
                    if (ret > 0) {
                        received += ret;
                    } else {
                        // ret == -1 (EAGAIN)
                        Thread.onSpinWait();
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // 简单封装 libc read 以供接收端实时清空
    private int readRaw(int fd, MemorySegment buf) {
        // 使用之前定义的 Linker
        try {
            return (int) java.lang.invoke.MethodHandles.lookup().findStatic(IoUringBenchmark.class, "libcRead", 
                java.lang.invoke.MethodType.methodType(int.class, int.class, MemorySegment.class, long.class))
                .invoke(fd, buf, buf.byteSize());
        } catch (Throwable e) {
            return -1;
        }
    }

    private static final java.lang.invoke.MethodHandle READ_MH;
    static {
        java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
        java.lang.foreign.SymbolLookup libc = linker.defaultLookup();
        READ_MH = linker.downcallHandle(
            libc.find("read").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_LONG)
        );
    }

    public static int libcRead(int fd, MemorySegment buf, long len) throws Throwable {
        return (int) READ_MH.invokeExact(fd, buf, len);
    }
}
