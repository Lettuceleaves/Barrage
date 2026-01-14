package com.barrage.engine;

import com.barrage.kernel.config.BasicConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final long totalTargetQps; // 总目标 QPS

    // 计数器
    private final LongAdder respCounter; // 实际响应 QPS
    private final LongAdder sentCounter; // 实际发送 QPS

    private final HttpMessage requestTemplate;
    private final int batchSize;
    private final Arena globalArena = Arena.ofShared();
    private MemorySegment batchedRequest;

    public ClientEngine(String targetHost, int targetPort, int threads,
                        long targetQps,
                        LongAdder respCounter, LongAdder sentCounter,
                        HttpMessage requestTemplate) {
        try {
            this.targetIp = InetAddress.getByName(targetHost).getHostAddress();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.targetPort = targetPort;
        this.threads = threads;
        this.totalTargetQps = targetQps;
        this.respCounter = respCounter;
        this.sentCounter = sentCounter;
        this.requestTemplate = requestTemplate;
        this.batchSize = BasicConfig.getBATCH_SIZE();

        prepareBatchedRequest();
    }

    private void prepareBatchedRequest() {
        long singleLen = requestTemplate.length();
        long totalLen = singleLen * batchSize;
        this.batchedRequest = globalArena.allocate(totalLen);
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(requestTemplate.segment(), 0, batchedRequest, i * singleLen, singleLen);
        }
    }

    public void start() {
        // 将总 QPS 分摊到每个线程
        long qpsPerThread = Math.max(1, totalTargetQps / threads);
        System.out.println("[ClientEngine] Target QPS: " + totalTargetQps + " (Per Thread: " + qpsPerThread + ")");

        for (int i = 0; i < threads; i++) {
            new Thread(new ClientWorker(qpsPerThread), "client-worker-" + i).start();
        }
    }

    private class ClientWorker implements Runnable {
        private final long targetQps;
        private final boolean[] isWritePending; // 防止对同一个 socket 重复提交写

        // 令牌桶状态
        private double tokens = 0;
        private long lastTime = System.nanoTime();

        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;
        private static final int HTTP_HEADER_INT = 0x50545448;

        ClientWorker(long targetQps) {
            this.targetQps = targetQps;
            // 容量冗余一点，防止越界
            int capacity = BasicConfig.getCONNS_PER_CLIENT() + 16;
            this.isWritePending = new boolean[capacity];
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                int conns = BasicConfig.getCONNS_PER_CLIENT();
                MemoryArena memoryArena = new MemoryArena(arena, conns + 16);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 初始建连
                for (int i = 0; i < conns; i++) {
                    setupConnection(ring, memoryArena);
                }
                ring.submit();

                while (true) {
                    boolean anyRequestAdded = false; // 【关键修复】：标记本轮是否有新 SQE 加入

                    // --- 1. 令牌桶生成逻辑 ---
                    long now = System.nanoTime();
                    double deltaSec = (now - lastTime) / 1_000_000_000.0;
                    lastTime = now;

                    tokens += deltaSec * targetQps;
                    // 限制最大突发（Burst）：最多积攒 0.1 秒的量
                    double maxBurst = targetQps * 0.1;
                    if (tokens > maxBurst) {
                        tokens = maxBurst;
                    }

                    // --- 2. 尝试发包（定速发射）---
                    for (int i = 0; i < conns; i++) {
                        if (tokens >= batchSize) {
                            if (!isWritePending[i]) {
                                int fd = memoryArena.getFd(i);
                                addWrite(ring, fd, i, memoryArena);

                                tokens -= batchSize;
                                sentCounter.add(batchSize);
                                isWritePending[i] = true;
                                anyRequestAdded = true; // 标记：加入了写请求
                            }
                        } else {
                            break; // 令牌不够，停止遍历
                        }
                    }

                    // --- 3. 处理 IO 完成事件 ---
                    int eventsProcessed = 0;
                    while (ring.peekCqe(cqe)) {
                        long userData = cqe.userData;
                        int idx = (int) userData;
                        int type = (int) (userData >>> 32);

                        if (cqe.res < 0) {
                            reconnect(memoryArena.getFd(idx), idx, ring, memoryArena);
                            isWritePending[idx] = false;
                            eventsProcessed++;
                            continue;
                        }

                        int fd = memoryArena.getFd(idx);

                        if (type == EVENT_WRITE) {
                            isWritePending[idx] = false; // 写完解锁
                        } else if (type == EVENT_READ) {
                            int res = cqe.res;
                            if (res == 0) {
                                reconnect(fd, idx, ring, memoryArena);
                                isWritePending[idx] = false;
                            } else {
                                int found = countResponsesInBuffer(memoryArena.getBuffer(idx), res);
                                if (found > 0) respCounter.add(found);

                                // 读完继续挂起读
                                addRead(ring, fd, idx, memoryArena);
                                anyRequestAdded = true; // 标记：加入了读请求
                            }
                        }
                        eventsProcessed++;
                    }

                    // 【核心修复逻辑】：
                    // 如果处理了事件(eventsProcessed > 0) 或者 添加了新请求(anyRequestAdded)
                    // 都必须调用 submit，否则新加入的请求会卡在 SQ Ring 里发不出去
                    if (eventsProcessed > 0 || anyRequestAdded) {
                        ring.submit();
                    } else {
                        // 如果既没发包也没收包，且令牌不足，短暂休眠省 CPU
                        if (tokens < batchSize) {
                            LockSupport.parkNanos(100);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void setupConnection(IoUring ring, MemoryArena arena) throws IOException {
            NativeSocket s = new NativeSocket();
            if (s.connect(targetIp, targetPort)) {
                int fd = s.getFd();
                int idx = arena.allocate();
                arena.setFd(idx, fd);
                isWritePending[idx] = false;
                // 只挂 Read，Write 由令牌桶触发
                addRead(ring, fd, idx, arena);
            }
        }

        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            try { new NativeSocket(oldFd).close(); } catch (Exception ignored) {}
            NativeSocket s = null;
            while (true) {
                try {
                    s = new NativeSocket();
                    if (s.connect(targetIp, targetPort)) {
                        int newFd = s.getFd();
                        arena.setFd(idx, newFd);
                        isWritePending[idx] = false;
                        addRead(ring, newFd, idx, arena);
                        return;
                    }
                } catch (IOException e) { if (s!=null) try{s.close();}catch(Exception ex){} }
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
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

        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepSend(sqe, fd, batchedRequest, (int) batchedRequest.byteSize(), 0);
            long packedData = ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);
            long packedData = ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }
    }
}