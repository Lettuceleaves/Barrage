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

    private void prepareBatchedRequest() {
        long singleLen = requestTemplate.length();
        long totalLen = singleLen * batchSize;
        this.batchedRequest = globalArena.allocate(totalLen);
        for (int i = 0; i < batchSize; i++) {
            MemorySegment.copy(requestTemplate.segment(), 0, batchedRequest, i * singleLen, singleLen);
        }
        System.out.println("[ClientEngine] Pre-filled batch request: " + batchSize + " requests, total " + totalLen + " bytes");
    }

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
        private static final int HTTP_HEADER_INT = 0x50545448; // "HTTP" 小端序

        ClientWorker(LongAdder counter) {
            this.counter = counter;
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined();
                 IoUring ring = new IoUring(GlobalConfig.getQUEUE_DEPTH())) {

                int conns = GlobalConfig.getCONNS_PER_CLIENT();
                MemoryArena memoryArena = new MemoryArena(arena, conns * (GlobalConfig.getIN_FLIGHT() + 4));
                IoUring.Cqe cqe = new IoUring.Cqe();

                for (int i = 0; i < conns; i++) {
                    connect(ring, memoryArena);
                }
                ring.submitAndWait(0);

                while (true) {
                    int processed = 0;
                    while (processed < batchSize && ring.peekCqe(cqe)) {
                        long userData = cqe.userData;
                        int idx = (int) userData;
                        int type = (int) (userData >>> 32);

                        if (cqe.res < 0) {
                            reconnect(memoryArena.getFd(idx), idx, ring, memoryArena);
                            processed++;
                            continue;
                        }

                        int fd = memoryArena.getFd(idx); // 修复作用域

                        if (type == EVENT_WRITE) {
                            addRead(ring, fd, idx, memoryArena);
                        } else { // EVENT_READ
                            int res = cqe.res;
                            if (res == 0) {
                                reconnect(fd, idx, ring, memoryArena);
                            } else {
                                MemorySegment buffer = memoryArena.getBuffer(idx);
                                int found = countResponsesInBuffer(buffer, res);
                                if (found > 0) {
                                    counter.add(found);
                                    addWrite(ring, fd, idx, memoryArena);
                                } else {
                                    addRead(ring, fd, idx, memoryArena);
                                }
                            }
                        }
                        processed++;
                    }
                    ring.submitAndWait(processed > 0 ? 0 : 1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 高性能 Buffer 扫描：统计出现多少个 HTTP 响应头
         * 这是一个热点方法，Graal 会将其彻底内联
         */
        private int countResponsesInBuffer(MemorySegment buffer, int length) {
            if (length < 4) return 0;
            int count = 0;
            // 每次跳过 4 字节进行滑动匹配，利用 int 比较加速
            for (long i = 0; i <= length - 4; i++) {
                if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, i) == HTTP_HEADER_INT) {
                    count++;
                    // 找到后可以根据平均响应大小增加步长，极致压榨性能
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
            // 使用外部类的 batchedRequest
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