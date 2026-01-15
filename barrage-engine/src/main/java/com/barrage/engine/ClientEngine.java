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
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * 高性能压测引擎核心。
 * <p>
 * 职责：
 * 1. 管理 IO 线程池 (ClientWorker)。
 * 2. 负责将 HttpTemplate 数据搬运到堆外内存 (Off-Heap Memory)。
 * 3. 基于 io_uring 实现全异步、零拷贝的发包与收包。
 */
public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final long totalTargetQps;      // 配置的上限 (天花板)
    private volatile long currentTargetQps; // 当前实时限速 (从 0 开始)

    // 统计计数器
    private final LongAdder respCounter;
    private final LongAdder sentCounter;

    // 数据模版与内存管理
    private final HttpTemplate requestTemplate;
    private final int batchSize;
    private final Arena globalArena = Arena.ofShared();
    private MemorySegment batchedRequest;

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

        // 在启动前，将模版数据“固化”到堆外内存
        prepareBatchedRequest();
    }

    public void setCurrentTargetQps(long qps) {
        this.currentTargetQps = qps;
    }

    public long getCurrentTargetQps() {
        return currentTargetQps;
    }

    public long getTotalTargetQps() {
        return totalTargetQps;
    }

    /**
     * 核心内存准备：将 Java 堆上的模版数据拷贝到 io_uring 可访问的堆外内存。
     * 实现了 Batching 优化，一次系统调用发送多个请求。
     */
    private void prepareBatchedRequest() {
        // 1. 获取源数据 (无论是文件 Raw 字节还是 Console 动态生成的字节)
        byte[] srcBytes = requestTemplate.toBytes();
        long singleLen = srcBytes.length;
        long totalLen = singleLen * batchSize;

        System.out.println("[Engine] Pre-allocating off-heap memory: " + totalLen + " bytes");

        // 2. 分配堆外内存
        this.batchedRequest = globalArena.allocate(totalLen);

        // 3. 创建临时 Heap Segment 用于拷贝
        MemorySegment srcSegment = MemorySegment.ofArray(srcBytes);

        // 4. 批量填充 (将单个请求复制 N 份)
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(srcSegment, 0, batchedRequest, i * singleLen, singleLen);
        }
    }

    public void start() {
        long qpsPerThread = Math.max(1, totalTargetQps / threads);
        System.out.println("[Engine] Starting " + threads + " workers. Target QPS: " + totalTargetQps
                + " (" + qpsPerThread + "/thread)");

        for (int i = 0; i < threads; i++) {
            new Thread(new ClientWorker(qpsPerThread), "client-worker-" + i).start();
        }
    }

    /**
     * 工作线程：基于 io_uring 的 Event Loop。
     */
    private class ClientWorker implements Runnable {
        private final long targetQps;
        private final boolean[] isWritePending;

        // 令牌桶状态
        private double tokens = 0;
        private long lastTime = System.nanoTime();

        // 常量定义
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;
        // HTTP/1.1 响应特征值 ("HTTP" 的 Little-Endian 整数表示)
        private static final int HTTP_HEADER_INT = 0x50545448;

        ClientWorker(long targetQps) {
            this.targetQps = targetQps;
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

                // 1. 建立初始连接
                for (int i = 0; i < conns; i++) {
                    setupConnection(ring, memoryArena);
                }
                ring.submit();

                // 2. 进入 Core Loop
                while (true) {
                    boolean anyRequestAdded = false; // 标记是否需要提交 SQE

                    // --- Phase A: 令牌桶更新 ---
                    long now = System.nanoTime();
                    double deltaSec = (now - lastTime) / 1_000_000_000.0;
                    lastTime = now;

                    long threadLimit = currentTargetQps / threads;

                    if (threadLimit > 0) {
                        tokens += deltaSec * threadLimit;
                        // 允许一小段突发，防止调度抖动
                        double maxBurst = Math.max(batchSize * 2, threadLimit * 0.1);
                        if (tokens > maxBurst) tokens = maxBurst;
                    } else {
                        tokens = 0; // 如果 limit 为 0，清空令牌
                    }

                    // --- Phase B: 发送请求 (Write) ---
                    for (int i = 0; i < conns; i++) {
                        if (tokens >= batchSize) {
                            if (!isWritePending[i]) {
                                int fd = memoryArena.getFd(i);
                                addWrite(ring, fd, i);

                                tokens -= batchSize;
                                sentCounter.add(batchSize);
                                isWritePending[i] = true;
                                anyRequestAdded = true;
                            }
                        } else {
                            break; // 令牌耗尽
                        }
                    }

                    // --- Phase C: 处理完成事件 (CQE) ---
                    int eventsProcessed = 0;
                    while (ring.peekCqe(cqe)) {
                        long userData = cqe.userData;
                        int idx = (int) userData;
                        int type = (int) (userData >>> 32);
                        int fd = memoryArena.getFd(idx);

                        // 错误处理 / 断连重连
                        if (cqe.res < 0) {
                            reconnect(fd, idx, ring, memoryArena);
                            isWritePending[idx] = false;
                            eventsProcessed++;
                            continue;
                        }

                        if (type == EVENT_WRITE) {
                            isWritePending[idx] = false; // 写操作完成，释放锁
                        } else if (type == EVENT_READ) {
                            int res = cqe.res;
                            if (res == 0) { // EOF
                                reconnect(fd, idx, ring, memoryArena);
                                isWritePending[idx] = false;
                            } else {
                                // 统计响应数
                                int found = countResponsesInBuffer(memoryArena.getBuffer(idx), res);
                                if (found > 0) respCounter.add(found);

                                // 读操作是一次性的，必须重新挂载
                                addRead(ring, fd, idx, memoryArena);
                                anyRequestAdded = true;
                            }
                        }
                        eventsProcessed++;
                    }

                    // --- Phase D: 提交与休眠 ---
                    if (eventsProcessed > 0 || anyRequestAdded) {
                        ring.submit();
                    } else {
                        // 无事可做且令牌不足时，短暂休眠避免空转烧 CPU
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
                addRead(ring, fd, idx, arena);
            }
        }

        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            // 关闭旧连接
            try { new NativeSocket(oldFd).close(); } catch (Exception ignored) {}

            // 简单的自旋重连策略
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
                } catch (IOException e) {
                    if (s != null) try { s.close(); } catch (Exception ex) {}
                }
                // 重连失败，稍作等待
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }

        /**
         * 极速统计响应个数。
         * 通过扫描 buffer 中 HTTP 协议头部的魔数 (0x50545448 -> "HTTP") 来估算响应数量。
         * 这种方式比完整解析 HTTP 协议快得多，适合压测场景。
         */
        private int countResponsesInBuffer(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;
            // 步长设为 1，确保不错过任何一个包（由于 TCP 粘包/拆包，头部可能出现在任何位置）
            // 优化：如果确定响应长度固定，可以增大步长
            for (long i = 0; i <= length - 4; i++) {
                if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, i) == HTTP_HEADER_INT) {
                    count++;
                    i += 8; // 跳过 "HTTP/1.1" 长度，减少扫描次数
                }
            }
            return count;
        }

        private void addWrite(IoUring r, int fd, int idx) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) {
                r.submit();
                sqe = r.nextSqe();
            }
            r.prepSend(sqe, fd, batchedRequest, (int) batchedRequest.byteSize(), 0);
            long packedData = ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) {
                r.submit();
                sqe = r.nextSqe();
            }
            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);
            long packedData = ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }
    }
}