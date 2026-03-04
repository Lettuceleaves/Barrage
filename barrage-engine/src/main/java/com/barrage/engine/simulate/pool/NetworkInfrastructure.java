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
 * 网络基础设施层 (M:N 模型中的 N) - Zero-Copy RingBuffer Edition.
 * <p>
 * 负责管理底层的物理 IO 线程 (IoLane) 和 socket 连接池。它是连接虚拟用户 (User Layer) 和 操作系统内核 (Kernel
 * Layer) 的桥梁。
 * <p>
 * <b>架构特性：</b>
 * <ul>
 * <li><b>M:N 线程模型：</b> 上层 M 个虚拟用户被动态映射到底层 N 个物理 IO 线程。通常 N = CPU Cores。</li>
 * <li><b>极致零拷贝：</b> IO 线程读取到数据后，不进行内存拷贝，而是直接将 RingBuffer 的物理地址传递给用户态。</li>
 * <li><b>环形缓冲 (Ring Buffer)：</b> 每个 Socket 连接维护深度的接收缓冲环，允许 User 线程和 IO
 * 线程并行处理而不发生竞态覆盖。</li>
 * </ul>
 */
@SuppressFBWarnings({ "EI_EXPOSE_REP", "MS_CANNOT_BE_FINAL" })
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
     * 提交异步请求任务 (Zero-Copy 指针传递版)。
     * <p>
     * 将发送请求委托给底层的 IO 线程。该方法是非阻塞的，但返回的 Future 需要在虚拟线程中被 await。
     *
     * @param userId       发起请求的用户 ID (用于负载均衡路由)
     * @param reqData      请求数据段 (发送 Payload)
     * @param userSlotMeta 用户 Slot 的元数据区域 (用于回写 Response 的 Address 和 Length)
     * @return 一个 CompletableFuture，当请求发送完毕且<b>接收到响应头</b>时完成
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
        for (IoLane lane : lanes)
            lane.close();
    }

    // =========================================
    // 内部类：IO 泳道 (IoLane)
    // =========================================
    private class IoLane implements Runnable {
        private final int id;
        private final String ip;
        private final int port;
        private final int maxConns;

        // 无锁队列：任务 & 重连结果
        private final Queue<IoTask> taskQueue = new ConcurrentLinkedQueue<>();
        private final Queue<ReconnectResult> reconnectResultQueue = new ConcurrentLinkedQueue<>();

        // 线程封闭资源
        private IoUring ring;
        private Arena laneArena; // Shared Arena
        private ConnectionSlot[] slots;

        // 常量定义
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

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
            this.laneArena = Arena.ofShared();

            try {
                this.ring = new IoUring(BasicConfig.getQUEUE_DEPTH());
                this.slots = new ConnectionSlot[maxConns];

                // Slab Allocation
                long totalSize = (long) maxConns * RING_DEPTH * READ_BUFFER_SIZE;
                MemorySegment globalBuffer = laneArena.allocate(totalSize, 64);

                // 初始化连接 (首次启动仍同步，保证基本可用性，或者也可改为异步)
                for (int i = 0; i < maxConns; i++) {
                    slots[i] = new ConnectionSlot(RING_DEPTH);
                    for (int r = 0; r < RING_DEPTH; r++) {
                        long offset = ((long) i * RING_DEPTH * READ_BUFFER_SIZE) + ((long) r * READ_BUFFER_SIZE);
                        slots[i].ringBuffers[r] = globalBuffer.asSlice(offset, READ_BUFFER_SIZE);
                    }
                    // 初始同步连接
                    setupConnectionSync(i);
                }

                ring.submit();
                IoUring.Cqe cqe = new IoUring.Cqe();

                // --- Reactor Loop ---
                while (running) {
                    boolean busy = false;

                    // 1. 处理重连完成的 FD
                    busy |= processReconnectQueue();

                    // 2. 处理发送任务
                    busy |= processTaskQueue();

                    // 3. 提交到内核
                    ring.submit();

                    // 4. 处理完成事件
                    while (ring.peekCqe(cqe)) {
                        handleCqe(cqe);
                        busy = true;
                    }

                    // 5. 自旋等待
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

        private boolean processReconnectQueue() {
            ReconnectResult res;
            boolean workDone = false;
            while ((res = reconnectResultQueue.poll()) != null) {
                int idx = res.slotIdx;
                int newFd = res.newFd;

                // 关闭旧的（如果存在）
                int oldFd = slots[idx].fd;
                if (oldFd > 0) {
                    try {
                        new NativeSocket(oldFd).close();
                    } catch (Exception ignored) {
                    }
                }

                slots[idx].fd = newFd;
                // 重新提交 Read 请求
                if (newFd > 0) {
                    try {
                        prepRead(newFd, idx, 0, slots[idx].ringBuffers[0]);
                        System.out.printf("[IoLane-%d] Slot %d reconnected async (FD=%d)%n", id, idx, newFd);
                    } catch (IOException e) {
                        // 如果这时候还出错，只能再重试
                        triggerAsyncReconnect(idx);
                    }
                }
                workDone = true;
            }
            return workDone;
        }

        private boolean processTaskQueue() {
            IoTask task;
            int processed = 0;
            while (processed++ < 128 && (task = taskQueue.poll()) != null) {
                int connIdx = (int) (task.userId % maxConns);
                ConnectionSlot slot = slots[connIdx];

                if (slot.fd <= 0) {
                    // 连接不可用，直接报错，让上层（Agent）重试或处理
                    task.future.completeExceptionally(new IOException("Connection unavailable (reconnecting)"));
                    continue;
                }

                slot.pendingTasks.offer(task);
                try {
                    prepSend(slot.fd, connIdx, task.requestData);
                    System.out.printf("[IoLane-%d] Slot %d SEND userId=%d (%d bytes)%n",
                            id, connIdx, task.userId, task.requestData.byteSize());
                } catch (IOException e) {
                    task.future.completeExceptionally(e);
                    slot.pendingTasks.pollLast(); // Revert offer
                    handleIoError(connIdx, -1); // 触发重连
                }
            }
            return processed > 0;
        }

        private void handleCqe(IoUring.Cqe cqe) {
            long userData = cqe.userData;
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

                // Zero-Copy Pointer Passing
                if (!slot.pendingTasks.isEmpty()) {
                    IoTask task = slot.pendingTasks.poll();
                    MemorySegment activeBuffer = slot.ringBuffers[bufIdx];
                    task.userSlotMeta.set(ValueLayout.JAVA_LONG, UserSlotLayout.OFFSET_RESP_PTR,
                            activeBuffer.address());
                    task.userSlotMeta.set(ValueLayout.JAVA_LONG, UserSlotLayout.OFFSET_RESP_LEN, (long) len);
                    task.future.complete(null);
                    System.out.printf("[IoLane-%d] Slot %d RECV userId=%d (%d bytes)%n",
                            id, connIdx, task.userId, len);
                }

                // Next Read
                int nextBufIdx = (bufIdx + 1) % RING_DEPTH;
                try {
                    prepRead(slot.fd, connIdx, nextBufIdx, slot.ringBuffers[nextBufIdx]);
                } catch (IOException e) {
                    handleIoError(connIdx, -1);
                }
            }
        }

        // --- Low Level Setup ---

        private void prepSend(int fd, int connIdx, MemorySegment data) throws IOException {
            MemorySegment sqe = getSqe();
            ring.prepSend(sqe, fd, data, (int) data.byteSize(), 0);
            long userData = ((long) EVENT_WRITE << 32) | (connIdx << 16);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, userData);
        }

        private void prepRead(int fd, int connIdx, int bufIdx, MemorySegment buffer) throws IOException {
            MemorySegment sqe = getSqe();
            ring.prepRead(sqe, fd, buffer, (int) buffer.byteSize(), 0);
            long combinedIdx = (connIdx << 16) | bufIdx;
            long userData = ((long) EVENT_READ << 32) | combinedIdx;
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, userData);
        }

        private MemorySegment getSqe() throws IOException {
            MemorySegment sqe = ring.nextSqe();
            if (sqe == null) {
                ring.submit();
                sqe = ring.nextSqe();
                if (sqe == null)
                    throw new IOException("SQ Ring Full");
            }
            return sqe;
        }

        // --- Connection Management (Async Healer) ---

        private void setupConnectionSync(int idx) {
            try {
                NativeSocket s = new NativeSocket();
                s.setReuseAddr();
                if (s.connect(ip, port)) {
                    slots[idx].fd = s.getFd();
                    prepRead(slots[idx].fd, idx, 0, slots[idx].ringBuffers[0]);
                    System.out.printf("[IoLane-%d] Slot %d connected (FD=%d)%n", id, idx, slots[idx].fd);
                } else {
                    System.err.println("[IoLane] Initial connect failed for slot " + idx + ", scheduling async retry.");
                    slots[idx].fd = -1;
                    triggerAsyncReconnect(idx);
                }
            } catch (Exception e) {
                slots[idx].fd = -1;
                triggerAsyncReconnect(idx);
            }
        }

        /**
         * 触发异步重连 (Non-Blocking)
         * 将耗时的 connect 操作甩给虚拟线程去做
         */
        private void triggerAsyncReconnect(int idx) {
            // 防止重复触发? 简单起见，如果已经是 -1 可能正在连，但这里简化处理
            if (slots[idx].fd == -1) {
                // Check if already regenerating?
                // For simplicity, allowed.
            }
            slots[idx].fd = -1; // Ensure marked as down

            // Fail all pending
            ConnectionSlot slot = slots[idx];
            while (!slot.pendingTasks.isEmpty()) {
                slot.pendingTasks.poll().future.completeExceptionally(new IOException("Conn Reset"));
            }

            // Start Healer Task
            Thread.ofVirtual().start(() -> {
                // Backoff loop
                int attempt = 0;
                while (running) {
                    attempt++;
                    try {
                        if (attempt > 1)
                            Thread.sleep(Math.min(attempt * 100, 2000));

                        NativeSocket s = new NativeSocket();
                        s.setReuseAddr();
                        if (s.connect(ip, port)) {
                            // Success! Enqueue result
                            reconnectResultQueue.offer(new ReconnectResult(idx, s.getFd()));
                            break; // Exit healer thread
                        } else {
                            s.close();
                        }
                    } catch (Exception e) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            });
        }

        private void handleIoError(int idx, int errCode) {
            System.err.printf("[IoLane-%d] IO Error on slot %d: errCode=%d, triggering reconnect%n", id, idx, errCode);
            long oldFd = slots[idx].fd;
            if (oldFd > 0) {
                try {
                    new NativeSocket((int) oldFd).close();
                } catch (Exception ignored) {
                }
            }
            triggerAsyncReconnect(idx);
        }

        private void closeResources() {
            // Cleanup logic...
            if (slots != null) {
                for (ConnectionSlot slot : slots) {
                    if (slot != null && slot.fd > 0) {
                        try {
                            new NativeSocket(slot.fd).close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            try {
                if (ring != null)
                    ring.close();
            } catch (Exception ignored) {
            }
            if (laneArena != null && laneArena.scope().isAlive())
                laneArena.close();
        }

        public void close() {
            running = false;
        }
    }

    record ReconnectResult(int slotIdx, int newFd) {
    }

    /**
     * 连接槽位 (Connection Slot).
     * <p>
     * 维护单个 Socket 连接的运行时状态，包括文件描述符 (FD) 和接收缓冲区环。
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
            CompletableFuture<Void> future) {
    }
}