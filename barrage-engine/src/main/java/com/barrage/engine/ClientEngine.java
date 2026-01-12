package com.barrage.engine;

import com.barrage.kernel.config.GlobalConfig;
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
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 基于 Linux {@code io_uring} 的高性能 HTTP 流量生成引擎（压测客户端）。
 * <p>
 * 该类采用了 "Thread-Per-Core" 架构模型，每个工作线程维护独立的 {@link IoUring} 提交/完成队列，
 * 并通过 Java FFI 直接操作堆外内存，以实现极致的吞吐量（RPS）。
 *
 * <h2>核心架构与特性：</h2>
 * <ul>
 * <li><b>批量请求预填充 (Request Batching)：</b>
 * 在构造阶段利用共享的 {@link Arena} 分配连续的堆外内存 {@code batchedRequest}，
 * 将 HTTP 请求模版预先复制多次。发送时直接提交大块内存指针，大幅减少 {@code send} 系统调用次数和内存拷贝开销。</li>
 *
 * <li><b>HTTP 流水线 (Pipelining)：</b>
 * 通过 {@code GlobalConfig.getIN_FLIGHT()} 控制并发深度，允许在未收到响应前连续发送多个请求，
 * 充分填满网络带宽延迟积 (BDP)。</li>
 *
 * <li><b>零拷贝响应扫描 (Zero-Copy Scanning)：</b>
 * 摒弃传统的 HTTP 解析器，采用启发式算法 {@code countResponsesInBuffer} 直接扫描接收缓冲区。
 * 利用 {@code int} (4字节) 步长匹配 "HTTP" 魔法数 (SWAR 思想)，避免了将 {@link MemorySegment}
 * 转换为 Java String 或 byte[] 的开销。</li>
 *
 * <li><b>无锁指标聚合：</b>
 * 使用 {@link LongAdder} 跨线程聚合 QPS 数据，避免了在高并发下的 CAS 自旋竞争。</li>
 * </ul>
 *
 * <h2>内存模型：</h2>
 * <ul>
 * <li><b>Global Arena：</b> 存储只读的批量请求模版，跨所有 Worker 线程共享。</li>
 * <li><b>Confined Arena：</b> 每个 {@code ClientWorker} 拥有独立的栈封闭 Arena，用于管理 socket 描述符、SQE/CQE 队列及接收缓冲区。</li>
 * </ul>
 *
 * <h2>使用限制：</h2>
 * 仅支持 Linux 5.10+ 内核（需支持 {@code IORING_OP_SEND/RECV}）。
 * 目标服务器必须支持 HTTP/1.1 Keep-Alive。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see IoUring
 * @see com.barrage.kernel.io.NativeSocket
 */
public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final LongAdder qpsCounter;
    private final HttpMessage requestTemplate;
    private final int batchSize;

    // 必须使用全局 Arena，因为该 Buffer 跨多个线程共享且生命周期覆盖整个引擎
    private final Arena globalArena = Arena.ofShared();
    private MemorySegment batchedRequest;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Intentionally sharing LongAdder for metrics aggregation")
    public ClientEngine(String targetIp, int targetPort, int threads, LongAdder qpsCounter, HttpMessage requestTemplate) {
        this.targetIp = Objects.requireNonNull(targetIp);
        this.targetPort = targetPort;
        this.threads = threads;
        this.qpsCounter = Objects.requireNonNull(qpsCounter);
        this.requestTemplate = Objects.requireNonNull(requestTemplate);
        this.batchSize = GlobalConfig.getBATCH_SIZE();

        // 在构造阶段完成批量装填预处理
        prepareBatchedRequest();
    }

    /**
     * 预处理批量请求数据。
     * <p>
     * 将单个 HTTP 请求模版连续复制 {@code batchSize} 次到连续的堆外内存中。
     * 这样在发送时，只需传递一次指针和总长度，即可利用 TCP 流特性发送多条请求，
     * 极大地摊薄了系统调用开销。
     */
    private void prepareBatchedRequest() {
        long singleLen = requestTemplate.length();
        long totalLen = singleLen * batchSize;
        this.batchedRequest = globalArena.allocate(totalLen);
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(requestTemplate.segment(), 0, batchedRequest, i * singleLen, singleLen);
        }
        System.out.println("[ClientEngine] Pre-filled batch request: " + batchSize + " requests, total " + totalLen + " bytes");
    }

    /**
     * 启动工作线程。
     * <p>
     * 创建并启动 {@code threads} 个 {@link ClientWorker} 线程，
     * 每个线程独立绑定一个 CPU 核心（取决于 OS 调度），执行独立的 io_uring 事件循环。
     */
    public void start() {
        System.out.println("[ClientEngine] Starting " + threads + " workers...");
        for (int i = 0; i < threads; i++) {
            new Thread(new ClientWorker(qpsCounter), "client-worker-" + i).start();
        }
    }

    private class ClientWorker implements Runnable {
        private final LongAdder counter;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;
        // "HTTP" ASCII 对应的整数值 (小端序: 'H'(0x48), 'T'(0x54), 'T'(0x54), 'P'(0x50) -> 0x50545448)
        private static final int HTTP_HEADER_INT = 0x50545448;

        ClientWorker(LongAdder counter) {
            this.counter = counter;
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(GlobalConfig.getQUEUE_DEPTH())) {

                int conns = GlobalConfig.getCONNS_PER_CLIENT();
                // 为每个连接预分配读缓冲区，大小需覆盖 flight 窗口
                MemoryArena memoryArena = new MemoryArena(arena, conns * (GlobalConfig.getIN_FLIGHT() + 4));
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 初始建连
                for (int i = 0; i < conns; i++) {
                    connect(ring, memoryArena);
                }
                ring.submitAndWait(0);

                // 主事件循环
                while (true) {
                    int processed = 0;
                    // 批量处理 CQE，直到达到批次上限或队列为空
                    while (processed < batchSize && ring.peekCqe(cqe)) {
                        long userData = cqe.userData;
                        int idx = (int) userData;
                        int type = (int) (userData >>> 32);

                        if (cqe.res < 0) {
                            // 发生错误（如连接断开），执行重连逻辑
                            reconnect(memoryArena.getFd(idx), idx, ring, memoryArena);
                            processed++;
                            continue;
                        }

                        int fd = memoryArena.getFd(idx);

                        if (type == EVENT_WRITE) {
                            // 写完成，切换为读状态
                            addRead(ring, fd, idx, memoryArena);
                        } else { // EVENT_READ
                            int res = cqe.res;
                            if (res == 0) {
                                // EOF，对端关闭连接
                                reconnect(fd, idx, ring, memoryArena);
                            } else {
                                // 读取数据成功，扫描响应计数
                                MemorySegment buffer = memoryArena.getBuffer(idx);
                                int found = countResponsesInBuffer(buffer, res);
                                if (found > 0) {
                                    counter.add(found);
                                    // 收到响应后，继续发送下一批请求 (Pipeline)
                                    addWrite(ring, fd, idx, memoryArena);
                                } else {
                                    // 数据不完整，继续读取
                                    addRead(ring, fd, idx, memoryArena);
                                }
                            }
                        }
                        processed++;
                    }
                    // 如果处理了事件，非阻塞提交；否则进入阻塞等待，减少 CPU 空转
                    ring.submitAndWait(processed > 0 ? 0 : 1);
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * 高性能 Buffer 扫描：统计出现多少个 HTTP 响应头。
         * <p>
         * 利用 {@code int} (4字节) 比较代替逐字节比较，属于 SWAR (SIMD Within A Register) 的一种简单应用。
         * 该方法是热点代码，Graal 编译器应能将其内联并进行向量化优化。
         *
         * @param buffer 包含接收数据的内存段
         * @param length 数据长度
         * @return 识别到的 HTTP 响应数量
         */
        private int countResponsesInBuffer(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;
            // 每次读取 4 字节进行滑动匹配
            for (long i = 0; i <= length - 4; i++) {
                if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, i) == HTTP_HEADER_INT) {
                    count++;
                    // 启发式优化：找到一个 Header 后，跳过部分字节（假设最小响应头长度）
                    // 减少无效比较次数，极致压榨性能
                    i += 10;
                }
            }
            return count;
        }

        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            new NativeSocket(oldFd).close();
            arena.free(idx);
            connect(ring, arena);
        }

        private boolean connect(IoUring ring, MemoryArena arena) {
            NativeSocket s = null;
            try {
                s = new NativeSocket();
                if (!s.connect(targetIp, targetPort)) return false;
                int fd = s.getFd();
                // 连接建立后，预先填满 pipeline
                for (int k = 0; k < GlobalConfig.getIN_FLIGHT(); k++) {
                    int idx = arena.allocate();
                    arena.setFd(idx, fd);
                    addWrite(ring, fd, idx, arena);
                }
                return true;
            } catch (IOException e) {
                if (s != null) s.close();
                return false;
            }
        }

        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            // 复用外部类预先构建的 batchedRequest，避免重复拷贝
            r.prepSend(sqe, fd, batchedRequest, (int) batchedRequest.byteSize(), 0);
            long packedData = ((long) EVENT_WRITE << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), GlobalConfig.getREAD_SZ(), 0);
            long packedData = ((long) EVENT_READ << 32) | (idx & 0xFFFFFFFFL);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, packedData);
        }
    }
}