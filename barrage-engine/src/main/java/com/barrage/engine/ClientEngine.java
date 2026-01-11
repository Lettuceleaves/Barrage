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
 * 客户端高性能压力测试引擎。
 */
public class ClientEngine {
    private final String targetIp;
    private final int targetPort;
    private final int threads;
    private final LongAdder qpsCounter;
    private final HttpMessage requestTemplate;

    /**
     * 构造一个新的客户端引擎。
     * 修复了 EI_EXPOSE_REP2 警告：通过注解确认共享对象的合法性。
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Shared counter and template are required for high-concurrency statistics and zero-copy")
    public ClientEngine(String targetIp, int targetPort, int threads, LongAdder qpsCounter, HttpMessage requestTemplate) {
        this.targetIp = Objects.requireNonNull(targetIp);
        this.targetPort = targetPort;
        this.threads = threads;
        this.qpsCounter = Objects.requireNonNull(qpsCounter);
        this.requestTemplate = Objects.requireNonNull(requestTemplate);
    }

    /**
     * 启动引擎。
     */
    public void start() {
        System.out.println("[ClientEngine] Targeting " + targetIp + ":" + targetPort + " with " + threads + " threads");
        for (int i = 0; i < threads; i++) {
            // 将计数器透传给工作线程
            new Thread(new ClientWorker(qpsCounter), "client-worker-" + i).start();
        }
    }

    private class ClientWorker implements Runnable {
        private final LongAdder counter;
        private static final int EVENT_READ = 1;
        private static final int EVENT_WRITE = 2;

        ClientWorker(LongAdder counter) {
            this.counter = counter;
        }

        @Override
        @SuppressFBWarnings("REC_CATCH_EXCEPTION")
        public void run() {
            // 使用 GlobalConfig.getXXX() 确保 AOT 编译动态生效
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(GlobalConfig.getQUEUE_DEPTH())) {

                int poolCap = GlobalConfig.getCONNS_PER_CLIENT() * (GlobalConfig.getIN_FLIGHT() + 4);
                MemoryArena memoryArena = new MemoryArena(arena, poolCap);
                IoUring.Cqe cqe = new IoUring.Cqe();

                for (int i = 0; i < GlobalConfig.getCONNS_PER_CLIENT(); i++) {
                    connect(ring, memoryArena);
                }
                ring.submitAndWait(0);

                while (true) {
                    int cqeCount = 0;
                    while (cqeCount < GlobalConfig.getBATCH_SIZE() && ring.peekCqe(cqe)) {
                        processEvent(ring, cqe, memoryArena);
                        cqeCount++;
                    }
                    if (cqeCount > 0) ring.submitAndWait(0);
                    else ring.submitAndWait(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void processEvent(IoUring ring, IoUring.Cqe cqe, MemoryArena arena) throws IOException {
            int idx = (int) cqe.userData;
            int type = arena.getType(idx);
            int fd = arena.getFd(idx);

            if (cqe.res < 0) {
                reconnect(fd, idx, ring, arena);
                return;
            }

            if (type == EVENT_WRITE) {
                addRead(ring, fd, idx, arena);
            } else if (type == EVENT_READ) {
                if (cqe.res == 0) {
                    reconnect(fd, idx, ring, arena);
                } else {
                    MemorySegment buffer = arena.getBuffer(idx);
                    if (isNewResponse(buffer, cqe.res)) {
                        counter.increment();
                        addWrite(ring, fd, idx, arena);
                    } else {
                        addRead(ring, fd, idx, arena);
                    }
                }
            }
        }

        private boolean isNewResponse(MemorySegment buffer, int length) {
            if (length < 4) return false;
            // 识别 "HTTP" 字符
            return buffer.get(ValueLayout.JAVA_BYTE, 0) == 'H' &&
                    buffer.get(ValueLayout.JAVA_BYTE, 1) == 'T' &&
                    buffer.get(ValueLayout.JAVA_BYTE, 2) == 'T' &&
                    buffer.get(ValueLayout.JAVA_BYTE, 3) == 'P';
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
            while (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepSend(sqe, fd, requestTemplate.segment(), (int) requestTemplate.length(), 0);
            arena.setType(idx, EVENT_WRITE);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }

        private void addRead(IoUring r, int fd, int idx, MemoryArena arena) throws IOException {
            MemorySegment sqe = r.nextSqe();
            if (sqe == null) { r.submit(); sqe = r.nextSqe(); }
            r.prepRead(sqe, fd, arena.getBuffer(idx), GlobalConfig.getREAD_SZ(), 0);
            arena.setType(idx, EVENT_READ);
            sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, idx);
        }
    }
}