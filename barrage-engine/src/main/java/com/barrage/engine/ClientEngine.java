package com.barrage.engine;

import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpTemplate;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final long totalTargetQps;
    private volatile long currentTargetQps;

    private final LongAdder respCounter;
    private final LongAdder sentCounter;
    private final LongAdder totalLatencyMicros = new LongAdder();

    private final HttpTemplate requestTemplate;
    private final int batchSize;
    private final Arena globalArena = Arena.ofShared();
    private MemorySegment batchedRequest;

    private volatile boolean running = false;
    private final List<Thread> workers = new ArrayList<>();

    public ClientEngine(String targetHost, int targetPort, int threads,
                        long targetQps,
                        LongAdder respCounter, LongAdder sentCounter,
                        HttpTemplate requestTemplate) {
        try {
            this.targetIp = InetAddress.getByName(targetHost).getHostAddress();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve host: " + targetHost, e);
        }
        this.targetPort = targetPort;
        this.threads = threads;
        this.totalTargetQps = targetQps;
        this.currentTargetQps = 0;
        this.respCounter = respCounter;
        this.sentCounter = sentCounter;
        this.requestTemplate = requestTemplate;
        this.batchSize = BasicConfig.getBATCH_SIZE();

        prepareBatchedRequest();
    }

    // ... Getter/Setter 省略 ...
    public void setCurrentTargetQps(long qps) { this.currentTargetQps = qps; }
    public long getCurrentTargetQps() { return currentTargetQps; }
    public long getTotalLatencyMicros() { return totalLatencyMicros.sum(); }

    private void prepareBatchedRequest() {
        byte[] srcBytes = requestTemplate.toBytes();
        long singleLen = srcBytes.length;
        long totalLen = singleLen * batchSize;
        System.out.println("[Engine] Pre-allocating off-heap memory: " + totalLen + " bytes");
        this.batchedRequest = globalArena.allocate(totalLen);
        MemorySegment srcSegment = MemorySegment.ofArray(srcBytes);
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(srcSegment, 0, batchedRequest, i * singleLen, singleLen);
        }
    }

    public void start() {
        this.running = true;
        long qpsPerThread = Math.max(1, totalTargetQps / threads);
        System.out.println("[Engine] Starting " + threads + " workers.");

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new ClientWorker(qpsPerThread), "client-worker-" + i);
            workers.add(t);
            t.start();
        }
    }

    /**
     * 增强版停机逻辑
     */
    // 替换 ClientEngine.java 中的 shutdown 方法
    public void shutdown() {
        if (!running) return;
        System.out.println("[ClientEngine] Shutting down...");

        running = false;

        // 1. 狂发中断，唤醒所有线程
        for (Thread t : workers) {
            t.interrupt();
        }

        // 2. [优化] 使用 limit 机制快速 join，不再死等
        // 给所有线程总共 2 秒的时间退出，而不是每个线程 1 秒
        long deadline = System.currentTimeMillis() + 2000;

        for (Thread t : workers) {
            long timeLeft = deadline - System.currentTimeMillis();
            if (timeLeft <= 0) {
                break; // 时间到了，不等了，直接通过
            }
            try {
                t.join(timeLeft);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        workers.clear();

        if (globalArena.scope().isAlive()) {
            try {
                globalArena.close();
                System.out.println("[ClientEngine] Off-heap memory released.");
            } catch (Exception e) {
                // 忽略关闭时的异常 (可能是因为线程还没完全退干净，Arena 处于被占用状态)
                System.err.println("[ClientEngine] Warning: Memory arena close failed (force exit).");
            }
        }
    }

    private class ClientWorker implements Runnable {
        private final long targetQps;
        private final boolean[] isWritePending;

        private static final int RING_SIZE = 16;
        private static final int RING_MASK = RING_SIZE - 1;

        private final long[] timestamps;
        private final int[] batchCounts;

        // [FIX] 改为 int 数组，彻底解决 byte 溢出导致的死循环和 lag
        private final int[] writeIndices;
        private final int[] readIndices;

        private double tokens = 0;
        private long lastTime = System.nanoTime();

        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;
        private static final int HTTP_HEADER_INT = 0x50545448;

        ClientWorker(long targetQps) {
            this.targetQps = targetQps;
            int capacity = BasicConfig.getCONNS_PER_CLIENT() + 16;
            this.isWritePending = new boolean[capacity];

            this.timestamps = new long[capacity * RING_SIZE];
            this.batchCounts = new int[capacity * RING_SIZE];
            // [FIX] 初始化为 int[]
            this.writeIndices = new int[capacity];
            this.readIndices = new int[capacity];
        }

        private int getRingIndex(int connIdx, int slotIdx) {
            return connIdx * RING_SIZE + (slotIdx & RING_MASK);
        }

        private void resetConnectionState(int connIdx) {
            writeIndices[connIdx] = 0;
            readIndices[connIdx] = 0;
            isWritePending[connIdx] = false;
            for (int i = 0; i < RING_SIZE; i++) {
                int pos = getRingIndex(connIdx, i);
                timestamps[pos] = 0;
                batchCounts[pos] = 0;
            }
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                int conns = BasicConfig.getCONNS_PER_CLIENT();
                MemoryArena memoryArena = new MemoryArena(arena, conns + 16);
                IoUring.Cqe cqe = new IoUring.Cqe();

                for (int i = 0; i < conns; i++) {
                    setupConnection(ring, memoryArena);
                }
                ring.submit();
                while (running) {
                    // 响应中断，加速退出
                    if (Thread.currentThread().isInterrupted()) break;

                    boolean anyRequestAdded = false;

                    // --- Phase A ---
                    long now = System.nanoTime();
                    double deltaSec = (now - lastTime) / 1_000_000_000.0;
                    lastTime = now;
                    long threadLimit = currentTargetQps / threads;

                    if (threadLimit > 0) {
                        tokens += deltaSec * threadLimit;
                        double maxBurst = Math.max(batchSize * 2, threadLimit * 0.1);
                        if (tokens > maxBurst) tokens = maxBurst;
                    } else {
                        tokens = 0;
                    }

                    // --- Phase B ---
                    for (int i = 0; i < conns; i++) {
                        int wIdx = writeIndices[i];
                        int rIdx = readIndices[i];
                        // [FIX] int 比较，逻辑正确
                        if (wIdx - rIdx >= RING_SIZE) {
                            continue;
                        }

                        if (tokens >= batchSize) {
                            if (!isWritePending[i]) {
                                boolean success = tryAddWrite(ring, memoryArena.getFd(i), i);
                                if (success) {
                                    int ringPos = getRingIndex(i, wIdx);
                                    timestamps[ringPos] = System.nanoTime();
                                    batchCounts[ringPos] = batchSize;
                                    writeIndices[i]++;
                                    tokens -= batchSize;
                                    sentCounter.add(batchSize);
                                    isWritePending[i] = true;
                                    anyRequestAdded = true;
                                } else {
                                    ring.submit();
                                    anyRequestAdded = false;
                                    break;
                                }
                            }
                        } else {
                            break;
                        }
                    }

                    // --- Phase C ---
                    int eventsProcessed = 0;
                    while (ring.peekCqe(cqe)) {
                        long userData = cqe.userData;
                        int idx = (int) userData;
                        int type = (int) (userData >>> 32);
                        int fd = memoryArena.getFd(idx);

                        if (cqe.res < 0) {
                            reconnect(fd, idx, ring, memoryArena);
                            eventsProcessed++;
                            continue;
                        }

                        if (type == EVENT_WRITE) {
                            isWritePending[idx] = false;
                        } else if (type == EVENT_READ) {
                            int res = cqe.res;
                            if (res == 0) {
                                reconnect(fd, idx, ring, memoryArena);
                            } else {
                                int totalResponses = countResponsesInBuffer(memoryArena.getBuffer(idx), res);
                                if (totalResponses > 0) {
                                    respCounter.add(totalResponses);
                                    long receiveTime = System.nanoTime();
                                    int remainingResps = totalResponses;
                                    while (remainingResps > 0) {
                                        if (readIndices[idx] == writeIndices[idx]) break;
                                        int ringPos = getRingIndex(idx, readIndices[idx]);
                                        long startTime = timestamps[ringPos];
                                        int expectedInBatch = batchCounts[ringPos];
                                        long latencyMicros = (receiveTime - startTime) / 1000;
                                        if (remainingResps >= expectedInBatch) {
                                            totalLatencyMicros.add(latencyMicros * expectedInBatch);
                                            remainingResps -= expectedInBatch;
                                            readIndices[idx]++;
                                        } else {
                                            totalLatencyMicros.add(latencyMicros * remainingResps);
                                            batchCounts[ringPos] -= remainingResps;
                                            remainingResps = 0;
                                        }
                                    }
                                }
                                addRead(ring, fd, idx, memoryArena);
                                anyRequestAdded = true;
                            }
                        }
                        eventsProcessed++;
                    }

                    // --- Phase D ---
                    if (eventsProcessed > 0 || anyRequestAdded) {
                        ring.submit();
                    } else {
                        if (tokens < batchSize) {
                            // 响应中断
                            LockSupport.parkNanos(100);
                        }
                    }
                }
                closeAllSockets(memoryArena, conns);
            } catch (Exception e) {
                if (running) e.printStackTrace();
            }
        }

        private boolean tryAddWrite(IoUring r, int fd, int idx) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return false;
            r.prepSend(sqe, fd, batchedRequest, (int) batchedRequest.byteSize(), 0);
            long packedData = ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
            return true;
        }

        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            try { new NativeSocket(oldFd).close(); } catch (Exception ignored) {}
            resetConnectionState(idx);
            NativeSocket s = null;
            while (running) {
                try {
                    s = new NativeSocket();
                    if (s.connect(targetIp, targetPort)) {
                        int newFd = s.getFd();
                        arena.setFd(idx, newFd);
                        addRead(ring, newFd, idx, arena);
                        return;
                    }
                } catch (IOException e) { if (s != null) try { s.close(); } catch (Exception ex) {} }
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }

        // setupConnection, countResponsesInBuffer, addRead, closeAllSockets 保持不变 (同上)
        private void setupConnection(IoUring ring, MemoryArena arena) throws IOException {
            NativeSocket s = new NativeSocket();
            if (s.connect(targetIp, targetPort)) {
                int fd = s.getFd();
                int idx = arena.allocate();
                arena.setFd(idx, fd);
                resetConnectionState(idx);
                addRead(ring, fd, idx, arena);
            }
        }
        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            while (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);
            long packedData = ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }
        private int countResponsesInBuffer(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;
            for (long i = 0; i <= length - 4; i++) {
                if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, i) == HTTP_HEADER_INT) {
                    count++;
                    i += 8;
                }
            }
            return count;
        }
        private void closeAllSockets(MemoryArena arena, int maxConns) {
            for (int i = 0; i < maxConns; i++) {
                int fd = arena.getFd(i);
                if (fd > 0) try { new NativeSocket(fd).close(); } catch (Throwable ignored) {}
            }
        }
    }
}