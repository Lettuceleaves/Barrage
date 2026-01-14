package com.barrage.engine;

import com.barrage.kernel.config.BasicConfig;
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

/**
 * 基于 Linux {@code io_uring} 的高性能 HTTP 服务端引擎。
 * <p>
 * 该类实现了 Reactor 模式的变体（Multi-Reactors），采用 "Thread-Per-Core" 架构。
 * 通过绕过 JDK NIO 直接操作操作系统原语，实现了极低延迟和高吞吐量的网络处理能力。
 *
 * <h2>核心特性与修复：</h2>
 * <ul>
 * <li><b>端口极速复用 (SO_REUSEADDR)：</b>
 * 在 {@code bind} 之前显式调用 {@code setsockopt} 设置 {@code SO_REUSEADDR}，
 * 彻底解决了服务重启时常见的 "Bind fail: Address already in use (98)" 错误，
 * 允许在 TIME_WAIT 状态下立即绑定端口。</li>
 *
 * <li><b>完全零拷贝 (Zero-Copy)：</b>
 * HTTP 响应数据 {@code HttpMessage.RESPONSE_200} 存储在静态堆外内存中。
 * 发送时通过 {@code IORING_OP_SEND} 直接传输内存地址，无用户态数据拷贝。</li>
 *
 * <li><b>栈封闭内存管理 (Confined Arena)：</b>
 * 每个 Worker 线程使用独立的 {@link Arena#ofConfined()}，
 * 实现了无锁内存分配（Bump Pointer Allocation）且完全避免了垃圾回收（Zero-GC）。</li>
 *
 * <li><b>混合轮询策略：</b>
 * 事件循环采用“有任务时批处理，无任务时阻塞等待”的策略，平衡了 CPU 使用率与响应延迟。</li>
 * </ul>
 *
 * <h2>架构限制：</h2>
 * <ul>
 * <li>仅支持 Linux 5.10+ (需支持 {@code IORING_OP_ACCEPT/READ/WRITE})。</li>
 * <li>目前实现为固定响应逻辑（Always 200 OK），用于极致基准测试。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see IoUring
 * @see NativeSocket
 */
public class ServerEngine {
    private final int port;
    private final int threads;
    private int serverFd;

    /**
     * 构建服务端引擎。
     *
     * @param port    监听端口 (e.g., 8080)
     * @param threads Worker 线程数量，建议设置为 CPU 物理核心数
     */
    public ServerEngine(int port, int threads) {
        this.port = port;
        this.threads = threads;
    }

    /**
     * 初始化并启动服务端。
     * <p>
     * 启动流程：
     * <ol>
     * <li>创建原生 Socket 文件描述符。</li>
     * <li><b>关键步骤：</b>开启 {@code SO_REUSEADDR} 选项，防止端口被占用。</li>
     * <li>绑定端口并开启监听 (Backlog 由 {@code GlobalConfig} 控制)。</li>
     * <li>创建并启动指定数量的 Worker 线程，每个线程绑定独立的 io_uring 实例。</li>
     * </ol>
     *
     * @throws IOException 如果 Socket 创建、绑定或监听失败
     */
    public void start() throws IOException {
        // 1. 初始化监听 Socket
        NativeSocket s = new NativeSocket();

        // 核心修复点：在 bind 之前强制夺回端口
        // 这允许服务器在崩溃或快速重启后立即使用该端口，无需等待 TIME_WAIT 结束
        s.setReuseAddr();

        s.bind(port);
        // 设置足够的 Backlog 以应对百万级 QPS 的连接建立需求
        // 这里的队列深度决定了内核全连接队列的大小
        s.listen(BasicConfig.getQUEUE_DEPTH());

        this.serverFd = s.getFd();

        System.out.println("[ServerEngine] Listening on " + port + " (FD: " + serverFd + ")");
        System.out.println("[ServerEngine] Initializing " + threads + " worker threads (Thread-Per-Core)...");

        // 2. 启动 Worker 线程
        for (int i = 0; i < threads; i++) {
            new Thread(new ServerWorker(serverFd), "server-worker-" + i).start();
        }
    }

    /**
     * 核心工作线程。
     * <p>
     * 每个线程维护一个独立的 {@code io_uring} 提交/完成环。
     * 采用状态机模式处理 {@code ACCEPT} -> {@code READ} -> {@code WRITE} 生命周期。
     */
    private static class ServerWorker implements Runnable {
        private final int serverFd;

        private static final int EVENT_ACCEPT = 0;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        ServerWorker(int fd) { this.serverFd = fd; }

        /**
         * 线程主循环。
         * <p>
         * 使用 {@code try-with-resources} 确保 Arena 和 Ring 在线程退出时正确释放。
         * 整个生命周期内不产生任何 Java 堆对象垃圾。
         */
        @Override
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            // 使用 Confined Arena 确保线程本地内存分配的极高性能
            // 所有在此 Arena 分配的内存仅限当前线程访问，无需 volatile 或同步
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(BasicConfig.getQUEUE_DEPTH())) {

                // 内存池大小：连接数 * 2（Read/Write 槽位分离，预留充足空间）
                MemoryArena memoryArena = new MemoryArena(arena, BasicConfig.getQUEUE_DEPTH() * 2);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 提交初始 Accept 请求，将监听 Socket 加入 io_uring 轮询
                int initIdx = memoryArena.allocate();
                addAccept(ring, serverFd, initIdx, memoryArena);
                ring.submit();

                // 事件主循环
                while (true) {
                    int cqeCount = 0;
                    // 批量处理：这是保持高 QPS 的关键。
                    // 尽可能多地从 CQ (Completion Queue) 取出事件，摊薄 Java/Native 切换开销。
                    while (cqeCount < BasicConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }

                    if (cqeCount > 0) {
                        // 如果处理了事件，说明系统繁忙，立即提交新的 SQE 且不阻塞
                        ring.submitAndWait(0);
                    } else {
                        // 无事件时阻塞等待至少 1 个事件，避免 CPU 100% 空转 (Busy Wait)
                        ring.submitAndWait(1);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Worker Error] " + Thread.currentThread().getName() + " : " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 事件状态机处理器。
         * 根据 CQE 返回的 userData 路由到具体的处理逻辑。
         */
        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // 处理内核返回的错误 (res < 0)
            if (cqe.res < 0) {
                if (type == EVENT_ACCEPT) {
                    // Accept 失败通常是瞬时的（如文件描述符耗尽），重新提交
                    addAccept(ring, serverFd, idx, arena);
                } else {
                    // 读写失败（如连接 Reset），关闭 Socket 并回收内存槽位
                    new NativeSocket(fd).close();
                    arena.free(idx);
                }
                return;
            }

            switch (type) {
                case EVENT_ACCEPT -> {
                    // 1. 重新提交 Accept 以处理后续连接
                    addAccept(ring, serverFd, idx, arena);
                    // 2. 初始化新连接：分配内存并提交 Read 请求
                    int clientFd = cqe.res;
                    int readIdx = arena.allocate();
                    if (readIdx != -1) {
                        addRead(ring, clientFd, readIdx, arena);
                    } else {
                        // 内存池满，拒绝连接
                        new NativeSocket(clientFd).close();
                    }
                }
                case EVENT_READ -> {
                    if (cqe.res == 0) {
                        // EOF: 对端关闭连接
                        new NativeSocket(fd).close();
                        arena.free(idx);
                    } else {
                        // 读取成功，转为 Write 状态（Echo/Response 逻辑）
                        addWrite(ring, fd, idx, arena);
                    }
                }
                case EVENT_WRITE -> {
                    // 写入完成，重新转为 Read 状态 (Keep-Alive)
                    addRead(ring, fd, idx, arena);
                }
                // 修复 SpotBugs SF_SWITCH_NO_DEFAULT 警告
                default -> {
                    System.err.println("[Critical] Unknown event type: " + type);
                    // 理论上不应到达这里，可根据需要释放资源
                }
            }
        }

        private void addAccept(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 修正：直接设置 SQE 参数，避免 prepNop 可能导致的不确定性
            sqe.fill((byte) 0); // 必须清零，防止残留数据影响内核解析
            sqe.set(ValueLayout.JAVA_BYTE, NativeConstants.SQE_OFF_OPCODE, NativeConstants.IORING_OP_ACCEPT);
            sqe.set(ValueLayout.JAVA_INT, NativeConstants.SQE_OFF_FD, fd);

            arena.setEventInfo(idx, EVENT_ACCEPT, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            r.prepRead(sqe, fd, arena.getBuffer(idx), BasicConfig.getREAD_SZ(), 0);
            arena.setEventInfo(idx, EVENT_READ, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) return;

            // 零拷贝发送 HttpMessage 静态响应
            // 数据直接从 Global 堆外内存发送，无需复制到 Socket 缓冲区
            r.prepSend(sqe, fd, HttpMessage.RESPONSE_200.segment(), (int) HttpMessage.RESPONSE_200.length(), 0);
            arena.setEventInfo(idx, EVENT_WRITE, fd);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}