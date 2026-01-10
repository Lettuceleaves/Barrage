package com.barrage.engine;

import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.LongAdder;

import static com.barrage.kernel.config.GlobalConfig.*;

/**
 * 基于 io_uring 的高性能 HTTP 客户端负载生成引擎。
 * <p>
 * 该类负责初始化并管理多个客户端工作线程（Worker），用于向目标服务器发起高并发、高吞吐量的 HTTP 请求。
 * 它是 Barrage 压测核心的一部分，设计目标是利用现代 Linux 内核特性（io_uring）和 Java FFI（Project Panama）
 * 来突破传统 Java NIO 客户端的性能瓶颈。
 *
 * <h3>架构特点：</h3>
 * <ul>
 * <li><b>Thread-Per-Core 模型：</b> 每个 Worker 线程绑定一个独立的 {@link IoUring} 实例和 {@link MemoryArena}，完全消除线程间锁竞争。</li>
 * <li><b>HTTP Pipelining：</b> 支持在同一个 TCP 连接上并发发送多个请求（In-Flight），极大提高带宽利用率。</li>
 * <li><b>Off-Heap Memory：</b> 使用 {@link Arena} 进行堆外内存管理，减少 GC 压力。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final LongAdder qpsCounter;

    /**
     * 构造一个新的客户端引擎实例。
     *
     * @param targetIp   目标服务器 IP 地址
     * @param targetPort 目标服务器端口
     * @param threads    并发线程数（建议与 CPU 核心数相当）
     * @param qpsCounter 全局 QPS 计数器，所有 Worker 将向此计数器汇报成功请求
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Intentional shared mutable state for metrics aggregation")
    public ClientEngine(String targetIp, int targetPort, int threads, LongAdder qpsCounter) {
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.threads = threads;
        this.qpsCounter = qpsCounter;
    }

    /**
     * 启动客户端引擎。
     * <p>
     * 该方法会创建指定数量的 {@link ClientWorker} 线程并立即启动它们。
     * 每个线程将独立负责建立连接池并持续发送请求。
     */
    public void start() {
        System.out.println("[ClientEngine] Targeting " + targetIp + ":" + targetPort + " with " + threads + " threads");
        for (int i = 0; i < threads; i++) {
            new Thread(new ClientWorker(qpsCounter), "client-worker-" + i).start();
        }
    }

    /**
     * 客户端工作线程 (Client Worker)。
     * <p>
     * 这是一个独立的执行单元，负责维护自己的 io_uring 环、连接池和内存分配器。
     * 采用全异步、事件驱动的方式处理网络 IO。
     */
    private class ClientWorker implements Runnable {
        private final LongAdder counter;

        /** 事件类型：读完成 */
        private static final int EVENT_READ = 1;
        /** 事件类型：写完成 */
        private static final int EVENT_WRITE = 2;

        /**
         * 构造一个工作线程。
         *
         * @param counter 用于统计请求完成数的全局累加器
         */
        ClientWorker(LongAdder counter) {
            this.counter = counter;
        }

        /**
         * Worker 的主循环逻辑。
         * <p>
         * 流程如下：
         * <ol>
         * <li>申请线程封闭的堆外内存 {@link Arena}。</li>
         * <li>初始化 {@link IoUring} 和 {@link MemoryArena}。</li>
         * <li>批量建立 TCP 连接，并初始化 HTTP 管道化请求。</li>
         * <li>进入死循环，通过 {@code ring.submitAndWait} 等待 IO 完成事件 (CQE)。</li>
         * <li>分发处理事件，维持 "Write -> Read -> Write" 的循环。</li>
         * </ol>
         */
        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(QUEUE_DEPTH)) {

                // 计算所需内存池大小：连接数 * (InFlight数 + 冗余)
                // 每个 InFlight 请求都需要一个独立的内存槽位来保存上下文
                int poolCap = CONNS_PER_CLIENT * (IN_FLIGHT + 4);
                MemoryArena memoryArena = new MemoryArena(arena, poolCap);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 批量建立连接
                int pending = 0;
                for (int i = 0; i < CONNS_PER_CLIENT; i++) {
                    if (connect(ring, memoryArena)) pending++;
                }
                ring.submitAndWait(0);

                // 事件循环 (Event Loop)
                while (true) {
                    int cqeCount = 0;
                    // 批量处理完成队列中的事件，减少系统调用次数
                    while (cqeCount < BATCH_SIZE && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }
                    // 根据处理情况决定是否阻塞等待
                    if (cqeCount > 0) ring.submitAndWait(0);
                    else ring.submitAndWait(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 处理单个 IO 完成事件 (CQE)。
         * <p>
         * 根据事件类型（READ/WRITE）和结果（成功/失败/EOF）驱动状态机流转。
         *
         * @param ring   当前线程的 io_uring 实例
         * @param cqe    完成队列条目，包含 IO 结果和 User Data
         * @param arena  内存分配器，用于查找 fd 和上下文
         */
        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // IO 错误处理 (res < 0 表示 errno)
            if (cqe.res < 0) {
                reconnect(fd, idx, ring, arena);
                return;
            }

            if (type == EVENT_WRITE) {
                // 请求数据已写入网卡缓冲区，现在提交一个 Read 请求以等待服务器响应
                addRead(ring, fd, idx, arena);
            } else if (type == EVENT_READ) {
                if (cqe.res == 0) { // EOF: 服务器关闭了连接
                    reconnect(fd, idx, ring, arena);
                } else {
                    // 读取响应成功，视为一次完整的 QPS
                    counter.increment();
                    // 立即发送下一个请求，保持管道饱满
                    addWrite(ring, fd, idx, arena);
                }
            }
        }

        /**
         * 处理连接断开或错误，尝试重连。
         *
         * @param oldFd 旧的文件描述符
         * @param idx   旧的内存槽位索引
         * @param ring  io_uring 实例
         * @param arena 内存分配器
         */
        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            new NativeSocket(oldFd).close(); // 关闭旧 FD
            arena.free(idx); // 释放旧 Slot
            connect(ring, arena); // 尝试建立新连接
        }

        /**
         * 建立新的 TCP 连接并初始化管道化请求。
         * <p>
         * 这是一个关键方法。它不仅建立连接，还会在连接成功后立即向 Ring 中
         * 提交 {@code IN_FLIGHT} 个并发写请求。这确保了连接一旦建立，
         * 压力就会立即打满，而不是等待“发一个-回一个”的串行过程。
         *
         * @param ring  io_uring 实例
         * @param arena 内存分配器
         * @return 连接是否建立成功
         */
        private boolean connect(IoUring ring, MemoryArena arena) {
            NativeSocket s = null;
            boolean success = false;
            try {
                s = new NativeSocket();
                if (!s.connect(targetIp, targetPort)) return false;

                int fd = s.getFd();
                // 管道化：每个连接并发发送 IN_FLIGHT 个请求
                for (int k = 0; k < IN_FLIGHT; k++) {
                    int idx = arena.allocate();
                    arena.setFd(idx, fd);
                    addWrite(ring, fd, idx, arena);
                }
                success = true;
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                // 连接失败需关闭，成功则保留 fd 供后续 IO 使用
                if (!success && s != null) s.close();
            }
        }

        /**
         * 向 io_uring 提交队列 (SQ) 添加一个写请求。
         * <p>
         * 将 {@link HttpMessage#REQUEST_DEFAULT} 发送给服务器。
         * 并将 User Data 设置为当前的内存槽位索引 {@code idx}，同时标记类型为 {@link #EVENT_WRITE}。
         */
        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe(); if(sqe==null)return;
            r.prepSend(sqe, fd, HttpMessage.REQUEST_DEFAULT.segment(), HttpMessage.REQUEST_DEFAULT.length(), 0);

            arena.setType(idx, EVENT_WRITE);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        /**
         * 向 io_uring 提交队列 (SQ) 添加一个读请求。
         * <p>
         * 准备接收服务器的响应数据。使用 {@link MemoryArena} 中分配的 Buffer。
         * 并将 User Data 设置为当前的内存槽位索引 {@code idx}，同时标记类型为 {@link #EVENT_READ}。
         */
        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe(); if(sqe==null)return;
            r.prepRead(sqe, fd, arena.getBuffer(idx), READ_SZ, 0);

            arena.setType(idx, EVENT_READ);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}