package com.barrage.kernel.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存区域 (Arena) 管理器。
 * <p>
 * 封装了 JDK 25 的 {@link Arena} API，以提供堆外内存的分配和管理。
 * 默认实现基于 Thread-Confined Arena（线程受限），非常适合 Thread-per-Core 架构，
 * 因为它避免了多线程竞争开销。
 * </p>
 * <p>
 * 此类还支持 Bump-Pointer（碰撞指针）分配策略，通过预先分配后端存储并执行原始指针运算，
 * 实现零堆分配 (Zero-GC)。
 * </p>
 */
public class MemoryArena implements AutoCloseable, DirectHeap {

    private final Arena arena;
    private boolean trackSegments = true;

    /**
     * 使用标准 {@code Arena.allocate()} 方法分配的内存段列表。
     * 如果启用了 {@code trackSegments}，则用于手动释放追踪。
     */
    private final List<MemorySegment> allocatedSegments = new ArrayList<>();
    
    /**
     * 当前已分配字节总数的累加器。
     */
    private long totalAllocated = 0L;
    
    /**
     * 可配置的总内存分配最大限制。
     */
    private long maxTotalSize = Long.MAX_VALUE;

    /**
     * 用于 Bump-Pointer (Zero-GC) 分配的后端存储。
     */
    private MemorySegment backingStore;
    
    /**
     * 后端存储中的当前写入偏移量。
     */
    private long currentOffset = 0L;

    /**
     * 使用标准的局域 Arena 创建一个新的 MemoryArena。
     */
    public MemoryArena() {
        this.arena = Arena.ofConfined();
    }

    /**
     * 创建一个新的 MemoryArena 并预分配用于 Zero-GC 分配的后端存储。
     *
     * @param maxTotalSize 后端存储的大小（字节）。
     */
    public MemoryArena(long maxTotalSize) {
        this.arena = Arena.ofConfined();
        this.maxTotalSize = maxTotalSize;
        // 如果已知大小，则预分配 Zero-GC 后端存储
        if (maxTotalSize > 0 && maxTotalSize < Long.MAX_VALUE) {
            this.backingStore = arena.allocate(maxTotalSize);
        }
    }

    /**
     * 启用或禁用在列表中追踪单个内存段。
     * 禁用追踪可减少高频分配期间的堆开销。
     *
     * @param trackSegments {@code true} 表示启用追踪，{@code false} 表示禁用。
     */
    public void setTrackSegments(boolean trackSegments) {
        this.trackSegments = trackSegments;
    }

    /**
     * 分配指定大小的内存段。
     * 如果存在后端存储，则使用 Bump-Pointer 策略。
     *
     * @param byteSize 内存段的大小（字节）。
     * @return 已分配的 {@link MemorySegment}。
     */
    public MemorySegment allocate(long byteSize) {
        if (totalAllocated + byteSize > maxTotalSize) {
            throw new IllegalStateException("Allocation exceeds max total size");
        }
        
        MemorySegment segment;
        if (backingStore != null) {
            segment = backingStore.asSlice(currentOffset, byteSize);
            currentOffset += byteSize;
        } else {
            segment = arena.allocate(byteSize);
        }

        if (trackSegments) {
            allocatedSegments.add(segment);
        }
        totalAllocated += byteSize;
        return segment;
    }

    /**
     * 分配内存并返回原始地址。
     * 这是使用后端存储时实现 Zero-GC 性能的主要方式。
     *
     * @param byteSize 要分配的内存大小（字节）。
     * @return 原始内存地址。
     */
    @Override
    public long allocateAddress(long byteSize) {
        if (totalAllocated + byteSize > maxTotalSize) {
            throw new IllegalStateException("Allocation exceeds max total size");
        }
        
        if (backingStore != null) {
            long addr = backingStore.address() + currentOffset;
            currentOffset += byteSize;
            totalAllocated += byteSize;
            return addr;
        }

        // 回退到段分配（会在堆上分配 MemorySegment 对象）
        MemorySegment segment = arena.allocate(byteSize);
        totalAllocated += byteSize;
        return segment.address();
    }

    /**
     * 分配具有特定对齐方式的内存。
     *
     * @param byteSize      内存段的大小（字节）。
     * @param byteAlignment 字节对齐（必须是 2 的幂）。
     * @return 已分配的 {@link MemorySegment}。
     */
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        if (totalAllocated + byteSize > maxTotalSize) {
            throw new IllegalStateException("Allocation exceeds max total size");
        }
        MemorySegment segment = arena.allocate(byteSize, byteAlignment);
        allocatedSegments.add(segment);
        totalAllocated += byteSize;
        return segment;
    }
    
    /**
     * 为 {@code int} 分配空间并初始化。
     *
     * @param value 初始值。
     * @return 指向已分配整数的 {@link MemorySegment}。
     */
    public MemorySegment allocateInt(int value) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT);
        segment.set(ValueLayout.JAVA_INT, 0, value);
        if (trackSegments) {
            allocatedSegments.add(segment);
        }
        totalAllocated += ValueLayout.JAVA_INT.byteSize();
        return segment;
    }

    /**
     * 关闭 Arena 并释放所有关联的堆外内存。
     * 从此 Arena 衍生的任何段都将失效。
     */
    @Override
    public void close() {
        allocatedSegments.clear();
        totalAllocated = 0L;
        arena.close();
    }
    
    /**
     * 返回底层的 JDK {@link Arena}。
     * 谨慎使用，因为直接访问会绕过追踪和安全检查。
     *
     * @return 原始 {@link Arena} 实例。
     */
    public Arena raw() {
        return arena;
    }

    /**
     * 释放一个内存段。如果启用了追踪，则将其从内部列表中移除。
     *
     * @param segment 要释放的内存段。
     */
    @Override
    public void free(MemorySegment segment) {
        if (trackSegments) {
            allocatedSegments.remove(segment);
        }
    }

    /**
     * 分配多个相同大小的块。
     *
     * @param blockSize  每个块的大小（字节）。
     * @param blockCount 要分配的块数。
     * @return {@link MemorySegment} 列表。
     */
    @Override
    public List<MemorySegment> allocateBlocks(long blockSize, int blockCount) {
        long required = blockSize * blockCount;
        if (totalAllocated + required > maxTotalSize) {
            throw new IllegalStateException("Allocation exceeds max total size");
        }
        List<MemorySegment> blocks = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            MemorySegment seg = allocate(blockSize);
            blocks.add(seg);
        }
        return blocks;
    }

    @Override
    public long getTotalAllocated() {
        return totalAllocated;
    }

    @Override
    public void setMaxTotalSize(long maxTotalSize) {
        this.maxTotalSize = maxTotalSize;
    }

    @Override
    public void free(List<MemorySegment> segments) {
        if (segments == null) {
            return;
        }
        for (MemorySegment segment : segments) {
            free(segment);
        }
    }
}
