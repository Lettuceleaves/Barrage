package com.barrage.kernel.gc;

import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.memory.MemoryArena;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 IoUring 和 MemoryArena 是否真正实现了零分配（Zero-Allocation）和零垃圾回收（Zero-GC）。
 * <p>
 * 此测试使用 {@link ThreadMXBean} 追踪线程分配的字节数，并使用 {@link WeakReference} 探测是否发生了 GC。
 * 它模拟了高频操作（如 100,000 次迭代），以确保在热路径中没有任何堆内存分配。
 * </p>
 */
public class ZeroAllocationTest {

    private static ThreadMXBean threadBean;

    @BeforeAll
    public static void setup() {
        threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threadBean.isThreadAllocatedMemorySupported()) {
            throw new RuntimeException("Thread allocated memory measurement is not supported");
        }
        threadBean.setThreadAllocatedMemoryEnabled(true);
    }

    private long getAllocatedBytes() {
        return threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /**
     * 用于检测是否发生了垃圾回收 (GC) 的简单工具类。
     */
    private static class GcDetector {
        private final WeakReference<Object> ref;

        public GcDetector() {
            // 创建一个哨兵对象，当我们丢弃本地引用时，它可以被 GC 回收
            Object sentinel = new Object();
            this.ref = new WeakReference<>(sentinel);
        }

        /**
         * 验证自探测器创建以来是否未发生任何 GC。
         *
         * @param context 错误消息中的上下文说明。
         */
        public void verifyNoGc(String context) {
            assertNotNull(ref.get(), "在 " + context + " 中检测到 GC！弱引用已丢失。");
        }
    }

    @Test
    public void testIoUringZeroAllocation() throws Exception {
        try (IoUring ring = new IoUring(32)) {
            ring.initLocalState();
            // Warmup
            for (int i = 0; i < 10_000; i++) {
                performIoUringOps(ring);
            }

            GcDetector detector = new GcDetector();
            
            // Phase 1: Measure after warmup
            long startBytes = getAllocatedBytes();
            int iterations = 100_000;
            for (int i = 0; i < iterations; i++) {
                performIoUringOps(ring);
            }
            long midBytes = getAllocatedBytes();
            
            // Phase 2: Measure another batch
            for (int i = 0; i < iterations; i++) {
                performIoUringOps(ring);
            }
            long endBytes = getAllocatedBytes();

            // Verify both byte count and GC status
            assertEquals(0, endBytes - midBytes, 
                String.format("IoUring: Allocated %d bytes between phases (per-iteration leak)", endBytes - midBytes));
            
            detector.verifyNoGc("IoUring hot path");
            
            System.out.println("IoUring verified: 0 bytes per-iteration & No GC (Total phase bytes: " + (midBytes - startBytes) + ")");
        }
    }

    private void performIoUringOps(IoUring ring) throws Exception {
        MemorySegment sqe = ring.nextSqe();
        if (sqe == null) {
            ring.submitAndGet();
            sqe = ring.nextSqe();
        }
        ring.prepNop(sqe);
        ring.submitAndGet();
        ring.waitComplete();
    }

    @Test
    public void testMemoryArenaZeroAllocation() {
        try (MemoryArena arena = new MemoryArena(100 * 1024 * 1024)) {
            arena.setTrackSegments(false);
            // Warmup
            for (int i = 0; i < 10_000; i++) {
                performArenaOps(arena);
            }

            GcDetector detector = new GcDetector();

            long startBytes = getAllocatedBytes();
            int iterations = 100_000;
            for (int i = 0; i < iterations; i++) {
                performArenaOps(arena);
            }
            long midBytes = getAllocatedBytes();

            for (int i = 0; i < iterations; i++) {
                performArenaOps(arena);
            }
            long endBytes = getAllocatedBytes();

            // Verify both byte count and GC status
            assertEquals(0, endBytes - midBytes, 
                String.format("MemoryArena: Allocated %d bytes between phases (per-iteration leak)", endBytes - midBytes));
            
            detector.verifyNoGc("MemoryArena address allocation");
            
            System.out.println("MemoryArena verified: 0 bytes per-iteration & No GC (Total phase bytes: " + (midBytes - startBytes) + ")");
        }
    }

    private void performArenaOps(MemoryArena arena) {
        arena.allocateAddress(128);
    }
}
