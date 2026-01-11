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
 * 客户端高性能压力测试引擎。
 * <p>
 * 该类基于 Linux {@code io_uring} 接口实现，采用异步非阻塞 IO 模型（Proactor 模式）。
 * 旨在通过最小化系统调用开销和内存拷贝，在单机环境下产生极高的 HTTP 请求压力。
 * </p>
 * <p>
 * <strong>核心改进：</strong>
 * 解决在高并发/大报文场景下的 QPS 归零及计数偏差问题。通过
 * 识别 HTTP 响应起始特征，确保跨 TCP 分片的响应能被正确识别并触发后续的 Pipeline 写操作。
 * </p>
 *
 * @author LettuceLeaves
 * @since 2026/1/11
 */
public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final LongAdder qpsCounter;
    private final HttpMessage requestTemplate;

    /**
     * 构造一个新的客户端引擎。
     *
     * @param targetIp        目标服务器 IP 地址
     * @param targetPort      目标服务器端口
     * @param threads         并发工作线程数
     * @param qpsCounter      用于全局 QPS 统计的累加器
     * @param requestTemplate 预定义的 HTTP 请求模板，减少运行时组包开销
     */
    public ClientEngine(String targetIp, int targetPort, int threads, LongAdder qpsCounter, HttpMessage requestTemplate) {
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.threads = threads;
        this.qpsCounter = qpsCounter;
        this.requestTemplate = requestTemplate;
    }

    /**
     * 启动引擎，初始化指定数量的工作线程。
     */
    public void start() {
        System.out.println("[ClientEngine] Targeting " + targetIp + ":" + targetPort + " with " + threads + " threads");
        for (int i = 0; i < threads; i++) {
            new Thread(new ClientWorker(qpsCounter), "client-worker-" + i).start();
        }
    }

    /**
     * 内部工作类，封装了 io_uring 的事件循环。
     */
    private class ClientWorker implements Runnable {
        private final LongAdder counter;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        /**
         * @param counter 关联的 QPS 计数器
         */
        ClientWorker(LongAdder counter) {
            this.counter = counter;
        }

        /**
         * 工作线程主逻辑。
         * 负责初始化 {@link IoUring} 提交队列和完成队列，维护内存池，并执行死循环事件监听。
         */
        @Override
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(QUEUE_DEPTH)) {

                // 计算内存池容量：(连接数) * (Pipeline深度 + 冗余缓冲)
                int poolCap = CONNS_PER_CLIENT * (IN_FLIGHT + 4);
                MemoryArena memoryArena = new MemoryArena(arena, poolCap);
                IoUring.Cqe cqe = new IoUring.Cqe();

                // 建立初始连接池并预热
                for (int i = 0; i < CONNS_PER_CLIENT; i++) {
                    connect(ring, memoryArena);
                }
                ring.submitAndWait(0);

                // 事件主循环：处理所有的 IO 完成事件
                while (true) {
                    int cqeCount = 0;
                    while (cqeCount < BATCH_SIZE && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }
                    // 如果本轮有处理事件，非阻塞提交；否则进入阻塞等待
                    if (cqeCount > 0) ring.submitAndWait(0);
                    else ring.submitAndWait(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 处理具体的完成事件（CQE）。
         *
         * @param ring  io_uring 实例
         * @param cqe   完成队列条目
         * @param arena 内存池管理器
         * @throws IOException 当执行 IO 操作失败时抛出
         */
        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) throws IOException {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            // 处理错误响应或对端关闭
            if (cqe.res < 0) {
                reconnect(fd, idx, ring, arena);
                return;
            }

            if (type == EVENT_WRITE) {
                // 写请求成功，立即注册读取响应的事件
                addRead(ring, fd, idx, arena);
            } else if (type == EVENT_READ) {
                if (cqe.res == 0) {
                    // 对端关闭连接
                    reconnect(fd, idx, ring, arena);
                } else {
                    MemorySegment buffer = arena.getBuffer(idx);

                    // 启发式协议解析：检查是否为一个完整响应的头部起始
                    if (isNewResponse(buffer, cqe.res)) {
                        counter.increment();
                        // 收到响应后，立即发送下一个请求以维持 Pipeline 压力
                        addWrite(ring, fd, idx, arena);
                    } else {
                        // 若为 body 片段，继续读取当前流，直至匹配到下一个 Header
                        addRead(ring, fd, idx, arena);
                    }
                }
            }
        }

        /**
         * 检查数据段是否包含 HTTP 响应的头部特征。
         * <p>
         * 通过验证前四个字节是否为 "HTTP" (ASCII: 72 84 84 80)。
         * </p>
         *
         * @param buffer 包含读取数据的内存段
         * @param length 实际读取到的字节数
         * @return 如果识别为新响应则返回 true
         */
        private boolean isNewResponse(MemorySegment buffer, int length) {
            if (length < 4) return false;
            byte b0 = buffer.get(ValueLayout.JAVA_BYTE, 0);
            byte b1 = buffer.get(ValueLayout.JAVA_BYTE, 1);
            byte b2 = buffer.get(ValueLayout.JAVA_BYTE, 2);
            byte b3 = buffer.get(ValueLayout.JAVA_BYTE, 3);
            return b0 == 'H' && b1 == 'T' && b2 == 'T' && b3 == 'P';
        }

        /**
         * 重新连接失效的句柄。
         */
        private void reconnect(int oldFd, int idx, IoUring ring, MemoryArena arena) {
            new NativeSocket(oldFd).close();
            arena.free(idx);
            connect(ring, arena);
        }

        /**
         * 初始化连接。
         * 建立 Socket 并根据 {@code IN_FLIGHT} 参数预填充发送队列。
         */
        private boolean connect(IoUring ring, MemoryArena arena) {
            NativeSocket s = null;
            try {
                s = new NativeSocket();
                if (!s.connect(targetIp, targetPort)) return false;

                int fd = s.getFd();
                for (int k = 0; k < IN_FLIGHT; k++) {
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

        /**
         * 提交异步写操作（Send）。
         */
        private void addWrite(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            while (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepSend(sqe, fd, requestTemplate.segment(), requestTemplate.length(), 0);
            arena.setType(idx, EVENT_WRITE);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        /**
         * 提交异步读操作（Read）。
         */
        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), READ_SZ, 0);
            arena.setType(idx, EVENT_READ);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}