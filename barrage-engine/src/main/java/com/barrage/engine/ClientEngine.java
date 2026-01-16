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

    public void setCurrentTargetQps(long qps) { this.currentTargetQps = qps; }
    public long getCurrentTargetQps() { return currentTargetQps; }
    public long getTotalLatencyMicros() { return totalLatencyMicros.sum(); }

    private void prepareBatchedRequest() {
        byte[] srcBytes = requestTemplate.toBytes();
        long totalLen = (long) srcBytes.length * batchSize;
        this.batchedRequest = globalArena.allocate(totalLen);
        MemorySegment srcSegment = MemorySegment.ofArray(srcBytes);
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(srcSegment, 0, batchedRequest, (long) i * srcBytes.length, srcBytes.length);
        }
    }

    public void start() {
        this.running = true;
        // 这里的 qpsPerThread 只是个初始参考值，实际运行中会动态计算
        long qpsPerThread = Math.max(1, totalTargetQps / threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new ClientWorker(qpsPerThread), "client-worker-" + i);
            workers.add(t);
            t.start();
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        for (Thread t : workers) t.interrupt();
        long deadline = System.currentTimeMillis() + 2000;
        for (Thread t : workers) {
            long timeLeft = deadline - System.currentTimeMillis();
            if (timeLeft > 0) {
                try { t.join(timeLeft); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        workers.clear();
        if (globalArena.scope().isAlive()) {
            try { globalArena.close(); } catch (Exception ignored) {}
        }
    }

    private class ClientWorker implements Runnable {
        private final long initialTargetQps;
        private final boolean[] isWritePending;
        private final int[] writeIndices;
        private final int[] readIndices;

        // 累加器：解决 TCP 拆包导致的读指针卡死 (Fix Deadlock)
        private final int[] responseAccumulator;

        // 采样统计
        private final long[] lastSampleTime;
        private final int[] sampleTargetIdx;
        private static final int SAMPLE_STEP = 8;

        private double tokens = 0;
        private long lastTime = System.nanoTime();
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;
        private static final int HTTP_HEADER_INT = 0x50545448;

        ClientWorker(long targetQps) {
            this.initialTargetQps = targetQps;
            int capacity = BasicConfig.getCONNS_PER_CLIENT() + 16;
            this.isWritePending = new boolean[capacity];
            this.writeIndices = new int[capacity];
            this.readIndices = new int[capacity];
            this.lastSampleTime = new long[capacity];
            this.sampleTargetIdx = new int[capacity];
            this.responseAccumulator = new int[capacity];
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                int conns = BasicConfig.getCONNS_PER_CLIENT();
                MemoryArena memoryArena = new MemoryArena(arena, conns + 16);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 1. 建立连接 (带重试机制，防止 Worker 崩溃)
                for (int i = 0; i < conns; i++) {
                    setupConnectionSafe(ring, memoryArena, i);
                }
                ring.submit();

                while (running) {
                    if (Thread.currentThread().isInterrupted()) break;
                    boolean busy = false;

                    // --- 2. 令牌桶 (修复容量问题) ---
                    long now = System.nanoTime();
                    // 动态获取当前总目标，分摊到每个线程
                    double currentThreadTarget = (double) currentTargetQps / threads;
                    tokens += (now - lastTime) / 1e9 * currentThreadTarget;
                    lastTime = now;

                    // [Fix] 容量扩大到 1000 倍 Batch，防止因 CPU 抖动导致的令牌溢出丢失
                    double maxTokens = batchSize * 1000.0;
                    if (tokens > maxTokens) tokens = maxTokens;

                    // --- 3. 发送逻辑 (Phase B) ---
                    if (tokens >= batchSize) {
                        for (int i = 0; i < conns; i++) {
                            // [Fix] 背压窗口扩大到 4096 (约 6.5w 并发请求)，解锁 400W+ QPS
                            if (writeIndices[i] - readIndices[i] > 4096) continue;

                            if (tokens >= batchSize && !isWritePending[i]) {
                                // 检查连接是否有效 (fd > 0)
                                int fd = memoryArena.getFd(i);
                                if (fd <= 0) {
                                    reconnect(0, i, ring, memoryArena); // 尝试重连
                                    continue;
                                }

                                if (tryAddWrite(ring, fd, i)) {
                                    if (writeIndices[i] >= sampleTargetIdx[i]) {
                                        lastSampleTime[i] = System.nanoTime();
                                        sampleTargetIdx[i] = writeIndices[i] + SAMPLE_STEP;
                                    }
                                    writeIndices[i]++;
                                    tokens -= batchSize;
                                    sentCounter.add(batchSize);
                                    isWritePending[i] = true;
                                    busy = true;
                                } else {
                                    ring.submit();
                                    break;
                                }
                            }
                            if (tokens < batchSize) break;
                        }
                    }

                    // --- 4. 响应处理 (Phase C) ---
                    int processed = 0;
                    while (ring.peekCqe(cqe)) {
                        handleCqeFast(cqe, ring, memoryArena);
                        processed++;
                        busy = true;
                        // 允许一次处理更多响应，避免被积压的 CQE 淹没
                        if (processed > 256) break;
                    }

                    // --- 5. 等待策略 ---
                    if (busy) {
                        ring.submit();
                    } else {
                        // [Fix] 自旋等待，消除操作系统调度延迟
                        Thread.onSpinWait();
                    }
                }
                closeAllSockets(memoryArena, conns);
            } catch (Exception e) {
                if (running) e.printStackTrace();
            }
        }

        private void setupConnectionSafe(IoUring ring, MemoryArena arena, int idx) {
            try {
                setupConnection(ring, arena, idx);
            } catch (Exception e) {
                // 连接失败不抛出异常，只打印日志，让 Loop 继续运行并尝试重连
                System.err.println("[ClientWorker] Initial connect failed for idx " + idx + ": " + e.getMessage());
                arena.setFd(idx, -1); // 标记为无效
            }
        }

        private void handleCqeFast(IoUring.Cqe cqe, IoUring ring, MemoryArena memoryArena) throws IOException {
            long userData = cqe.userData;
            int idx = (int) userData;
            int type = (int) (userData >>> 32);
            int fd = memoryArena.getFd(idx);

            if (cqe.res < 0) {
                reconnect(fd, idx, ring, memoryArena);
                return;
            }

            if (type == EVENT_WRITE) {
                isWritePending[idx] = false;
            } else if (type == EVENT_READ) {
                int res = cqe.res;
                if (res <= 0) {
                    reconnect(fd, idx, ring, memoryArena);
                } else {
                    int count = countResponsesInBuffer(memoryArena.getBuffer(idx), res);
                    if (count > 0) {
                        respCounter.add(count);

                        // [Fix] 累加器逻辑，解决拆包死锁
                        responseAccumulator[idx] += count;
                        int finishedBatches = responseAccumulator[idx] / batchSize;

                        if (finishedBatches > 0) {
                            int oldReadIdx = readIndices[idx];
                            readIndices[idx] += finishedBatches;
                            responseAccumulator[idx] %= batchSize; // 保留余数

                            // 采样结算
                            int lastSamplePoint = sampleTargetIdx[idx] - SAMPLE_STEP;
                            if (oldReadIdx <= lastSamplePoint && readIndices[idx] > lastSamplePoint) {
                                long lat = (System.nanoTime() - lastSampleTime[idx]) / 1000;
                                if (lat > 0 && lat < 5000000) {
                                    totalLatencyMicros.add(lat * SAMPLE_STEP * batchSize);
                                }
                            }
                        }
                    }
                    addRead(ring, fd, idx, memoryArena);
                }
            }
        }

        private int countResponsesInBuffer(MemorySegment buffer, int length) {
            if (length < 12) return 0;
            int count = 0;
            // [Fix] 客户端也使用跳跃扫描，虽然响应通常不如请求解析重，但优化总是好的
            for (long i = 0; i <= length - 4; ) {
                if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, i) == HTTP_HEADER_INT) {
                    count++;
                    i += 40; // 找到后跳过 40 字节
                } else {
                    i++;
                }
            }
            return count;
        }

        private boolean tryAddWrite(IoUring r, int fd, int idx) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return false;
            r.prepSend(sqe, fd, batchedRequest, (int) batchedRequest.byteSize(), 0);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL));
            return true;
        }

        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            if (oldFd > 0) try { new NativeSocket(oldFd).close(); } catch (Exception ignored) {}

            // 重置状态
            writeIndices[idx] = 0; readIndices[idx] = 0;
            isWritePending[idx] = false; sampleTargetIdx[idx] = 0;
            responseAccumulator[idx] = 0;

            NativeSocket s = null;
            // 简单的重连策略：尝试一次，不行就退出让主循环下一次再试
            // 避免在这里死循环阻塞主线程
            try {
                s = new NativeSocket();
                if (s.connect(targetIp, targetPort)) {
                    int newFd = s.getFd();
                    arena.setFd(idx, newFd);
                    addRead(ring, newFd, idx, arena);
                } else {
                    arena.setFd(idx, -1);
                }
            } catch (IOException e) {
                if (s != null) try { s.close(); } catch (Exception ex) {}
                arena.setFd(idx, -1);
            }
        }

        private void setupConnection(IoUring ring, MemoryArena arena, int idx) throws IOException {
            NativeSocket s = new NativeSocket();
            if (s.connect(targetIp, targetPort)) {
                int fd = s.getFd();
                arena.setFd(idx, fd);
                addRead(ring, fd, idx, arena);
            } else {
                throw new IOException("Connect failed");
            }
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            while (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL));
        }

        private void closeAllSockets(MemoryArena arena, int maxConns) {
            for (int i = 0; i < maxConns; i++) {
                int fd = arena.getFd(i);
                if (fd > 0) try { new NativeSocket(fd).close(); } catch (Throwable ignored) {}
            }
        }
    }
}