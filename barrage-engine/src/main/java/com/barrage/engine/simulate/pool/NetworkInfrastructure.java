package com.barrage.engine.simulate.pool;

import com.barrage.engine.simulate.context.UserSlotLayout;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 网络基础设施层 (M:N 模型中的 N) - Zero-Copy RingBuffer Edition
 * <p>
 * 架构特性：
 * 1. M:N 线程模型：M 个虚拟用户映射到 N 个物理 IO 线程。
 * 2. 极致零拷贝：IO 线程接收数据后，不拷贝到用户区，而是直接将 IO Buffer 的指针传递给用户。
 * 3. 环形缓冲：每个连接维护 4 个接收 Buffer，防止 User 还没读完就被 Kernel 覆盖。
 */
@SuppressFBWarnings({"EI_EXPOSE_REP", "MS_CANNOT_BE_FINAL"})
public class NetworkInfrastructure implements AutoCloseable {

    private final List<IoLane> lanes;
    private final int laneCount;
    private volatile boolean running = true;

    public NetworkInfrastructure() {
        this.laneCount = BasicConfig.getCLIENT_THREADS();
        this.lanes = new ArrayList<>(laneCount);

        String targetIp = BasicConfig.getIP();
        int targetPort = BasicConfig.getPORT();
        int totalConns = BasicConfig.getCONNS_PER_CLIENT();
        int connsPerLane = totalConns / laneCount;

        System.out.printf(">>> [Infra] Starting %d IO Lanes, targeting %s:%d, Total Conns: %d%n",
                laneCount, targetIp, targetPort, totalConns);

        for (int i = 0; i < laneCount; i++) {
            // 处理连接数分配余数
            int myConns = (i < totalConns % laneCount) ? connsPerLane + 1 : connsPerLane;
            IoLane lane = new IoLane(i, targetIp, targetPort, myConns);
            lanes.add(lane);

            Thread.ofPlatform()
                    .name("io-lane-" + i)
                    .start(lane);
        }
    }

    /**
     * 提交请求 (Zero-Copy 指针传递版)
     *
     * @param userId       用户 ID
     * @param reqData      请求数据 (发送 Payload)
     * @param userSlotMeta 用户 Slot 的元数据区域 (用于回写 Response 的 Address 和 Length)
     */
    public CompletableFuture<Void> submitRequest(long userId,
                                                 MemorySegment reqData,
                                                 MemorySegment userSlotMeta) {
        // 简单的取模路由
        int laneIdx = (int) (userId % laneCount);
        return lanes.get(laneIdx).submitTask(userId, reqData, userSlotMeta);
    }

    @Override
    public void close() {
        running = false;
        for (IoLane lane : lanes) lane.close();
    }

    // =========================================
    // 内部类：IO 泳道 (IoLane)
    // =========================================
    private class IoLane implements Runnable {
        private final int id;
        private final String ip;
        private final int port;
        private final int maxConns;

        // 无锁队列
        private final Queue<IoTask> taskQueue = new ConcurrentLinkedQueue<>();

        // 线程封闭资源
        private IoUring ring;
        private Arena laneArena; // 必须是 Shared，因为 User Thread 要读取这里的内存
        private ConnectionSlot[] slots;

        // 常量定义
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        // 每个连接的 Ring Buffer 深度 (防止覆盖)
        private static final int RING_DEPTH = 4;
        private static final int READ_BUFFER_SIZE = 4096;

        public IoLane(int id, String ip, int port, int maxConns) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.maxConns = maxConns;
        }

        public CompletableFuture<Void> submitTask(long userId, MemorySegment reqData, MemorySegment slotMeta) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            taskQueue.offer(new IoTask(userId, reqData, slotMeta, future));
            return future;
        }

        @Override
        public void run() {
            // 关键：使用 Shared Arena，让虚拟线程可以 "偷看" IO 线程的内存
            this.laneArena = Arena.ofShared();

            try {
                this.ring = new IoUring(BasicConfig.getQUEUE_DEPTH());
                this.slots = new ConnectionSlot[maxConns];

                // 预分配大块内存 (Slab Allocation)
                // 总大小 = 连接数 * 深度 * 单个Buffer大小
                long totalSize = (long) maxConns * RING_DEPTH * READ_BUFFER_SIZE;
                MemorySegment globalBuffer = laneArena.allocate(totalSize, 64);

                // 初始化连接
                for (int i = 0; i < maxConns; i++) {
                    slots[i] = new ConnectionSlot(RING_DEPTH);

                    // 切分内存给 Ring Buffers
                    for (int r = 0; r < RING_DEPTH; r++) {
                        long offset = ((long) i * RING_DEPTH * READ_BUFFER_SIZE) + ((long) r * READ_BUFFER_SIZE);
                        slots[i].ringBuffers[r] = globalBuffer.asSlice(offset, READ_BUFFER_SIZE);
                    }

                    setupConnectionSafe(i);
                }

                ring.submit();
                IoUring.Cqe cqe = new IoUring.Cqe();

                // --- Reactor Loop ---
                while (running) {
                    boolean busy = false;

                    // 1. 处理发送任务
                    busy |= processTaskQueue();

                    // 2. 提交到内核
                    ring.submit();

                    // 3. 处理完成事件
                    while (ring.peekCqe(cqe)) {
                        handleCqe(cqe);
                        busy = true;
                    }

                    // 4. 自旋等待 (为了低延迟)
                    if (!busy) {
                        Thread.onSpinWait();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                closeResources();
            }
        }

        private boolean processTaskQueue() {
            IoTask task;
            int processed = 0;
            while (processed++ < 128 && (task = taskQueue.poll()) != null) {
                int connIdx = (int) (task.userId % maxConns);
                ConnectionSlot slot = slots[connIdx];

                if (slot.fd <= 0) {
                    task.future.completeExceptionally(new IOException("Conn closed"));
                    continue;
                }

                slot.pendingTasks.offer(task);
                try {
                    // 发送请求数据
                    prepSend(slot.fd, connIdx, task.requestData);
                } catch (IOException e) {
                    task.future.completeExceptionally(e);
                    slot.pendingTasks.pollLast();
                }
            }
            return processed > 0;
        }

        private void handleCqe(IoUring.Cqe cqe) {
            long userData = cqe.userData;
            // 解码 userData
            // High 32: Type
            // Low 32:  ConnIndex (16bit) | BufferIndex (16bit)
            int type = (int) (userData >>> 32);
            int combinedIdx = (int) userData;
            int connIdx = combinedIdx >> 16;
            int bufIdx = combinedIdx & 0xFFFF;

            ConnectionSlot slot = slots[connIdx];

            if (cqe.res < 0) {
                handleIoError(connIdx, cqe.res);
                return;
            }

            if (type == EVENT_READ) {
                int len = cqe.res;
                if (len == 0) {
                    handleIoError(connIdx, -999); // EOF
                    return;
                }

                // ============================================
                // 核心逻辑：Zero-Copy Pointer Passing
                // ============================================
                if (!slot.pendingTasks.isEmpty()) {
                    IoTask task = slot.pendingTasks.poll();

                    // 1. 获取当前 Ring Buffer 的物理地址
                    MemorySegment activeBuffer = slot.ringBuffers[bufIdx];
                    long physAddr = activeBuffer.address();

                    // 2. 直接将 Address 和 Length 写入用户 Slot 的 Metadata 区域
                    // UserSlotLayout.OFFSET_RESP_PTR = 64
                    // UserSlotLayout.OFFSET_RESP_LEN = 72
                    task.userSlotMeta.set(ValueLayout.JAVA_LONG, UserSlotLayout.OFFSET_RESP_PTR, physAddr);
                    task.userSlotMeta.set(ValueLayout.JAVA_LONG, UserSlotLayout.OFFSET_RESP_LEN, (long) len);

                    // 3. 唤醒虚拟线程 (它将直接读取 activeBuffer 里的数据)
                    task.future.complete(null);
                }

                // 4. 准备下一次 Read
                // 使用 Ring 中的下一个 Buffer，避免立即覆盖当前数据
                int nextBufIdx = (bufIdx + 1) % RING_DEPTH;
                MemorySegment nextBuffer = slot.ringBuffers[nextBufIdx];

                try {
                    prepRead(slot.fd, connIdx, nextBufIdx, nextBuffer);
                } catch (IOException e) {
                    handleIoError(connIdx, -1);
                }

            } else if (type == EVENT_WRITE) {
                // Write 完成，通常不需要操作，除非要处理写回压
            }
        }

        // --- Low Level IO Helpers ---

        private void prepSend(int fd, int connIdx, MemorySegment data) throws IOException {
            MemorySegment sqe = getSqe();
            ring.prepSend(sqe, fd, data, (int) data.byteSize(), 0);
            // UserData: Type=WRITE | ConnIdx
            long userData = ((long) EVENT_WRITE << 32) | (connIdx << 16);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, userData);
        }

        private void prepRead(int fd, int connIdx, int bufIdx, MemorySegment buffer) throws IOException {
            MemorySegment sqe = getSqe();
            ring.prepRead(sqe, fd, buffer, (int) buffer.byteSize(), 0);

            // UserData: Type=READ | ConnIdx | BufIdx
            // 将 Buffer Index 编码进 userData，这样 handleCqe 才知道是哪个 buffer 回来了
            long combinedIdx = (connIdx << 16) | bufIdx;
            long userData = ((long) EVENT_READ << 32) | combinedIdx;

            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, userData);
        }

        private MemorySegment getSqe() throws IOException {
            MemorySegment sqe = ring.nextSqe();
            if (sqe == null) {
                ring.submit();
                sqe = ring.nextSqe();
                if (sqe == null) throw new IOException("SQ Ring Full");
            }
            return sqe;
        }

        // --- Connection Management ---

        private void setupConnectionSafe(int idx) {
            try {
                NativeSocket s = new NativeSocket();
                s.setReuseAddr();
                if (s.connect(ip, port)) {
                    slots[idx].fd = s.getFd();
                    // 初始使用 Buffer 0
                    prepRead(slots[idx].fd, idx, 0, slots[idx].ringBuffers[0]);
                } else {
                    System.err.println("[IoLane] Connect failed for slot " + idx);
                    slots[idx].fd = -1;
                }
            } catch (Exception e) {
                slots[idx].fd = -1;
            }
        }

        private void handleIoError(int idx, int errCode) {
            reconnect(idx);
            ConnectionSlot slot = slots[idx];
            while (!slot.pendingTasks.isEmpty()) {
                slot.pendingTasks.poll().future.completeExceptionally(
                        new IOException("IO Error: " + errCode)
                );
            }
        }

        private void reconnect(int idx) {
            int oldFd = slots[idx].fd;
            if (oldFd > 0) try { new NativeSocket(oldFd).close(); } catch (Exception ignored) {}
            slots[idx].fd = -1;
            setupConnectionSafe(idx);
        }

        private void closeResources() {
            if (slots != null) {
                for (ConnectionSlot slot : slots) {
                    if (slot != null && slot.fd > 0) {
                        try { new NativeSocket(slot.fd).close(); } catch (Exception ignored) {}
                    }
                }
            }
            try { if (ring != null) ring.close(); } catch (Exception ignored) {}
            if (laneArena != null && laneArena.scope().isAlive()) laneArena.close();
        }

        public void close() {
            running = false;
        }
    }

    /**
     * 连接槽位 (RingBuffer 状态管理)
     */
    private static class ConnectionSlot {
        int fd = -1;
        // 环形缓冲区：IO 线程轮询使用，给 Node 留出读取时间窗口
        final MemorySegment[] ringBuffers;
        final Deque<IoTask> pendingTasks = new ArrayDeque<>();

        ConnectionSlot(int depth) {
            this.ringBuffers = new MemorySegment[depth];
        }
    }

    /**
     * 内部任务载体
     */
    record IoTask(long userId,
                  MemorySegment requestData,
                  MemorySegment userSlotMeta, // 仅传入 Metadata 供回写指针
                  CompletableFuture<Void> future) {}
}