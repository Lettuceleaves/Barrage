package com.barrage.kernel.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 内存区域管理器 (Memory Arena).
 * <p>
 * 封装 JDK 25 的 {@link Arena} API，提供堆外内存的分配与管理。
 * 默认实现基于 Thread-Confined Arena，适用于 Thread-per-Core 架构，
 * 避免了多线程竞争的开销。
 * </p>
 */
public class MemoryArena implements AutoCloseable {

    private final Arena arena;

    /**
     * 创建一个新的线程封闭 (Confined) 内存区域.
     * 该区域只能由创建它的线程访问.
     */
    public MemoryArena() {
        this.arena = Arena.ofConfined();
    }

    /**
     * 分配指定字节大小的内存段.
     *
     * @param byteSize 分配大小 (字节)
     * @return 分配的 {@link MemorySegment}
     */
    public MemorySegment allocate(long byteSize) {
        return arena.allocate(byteSize);
    }

    /**
     * 分配指定字节大小并按照指定对齐方式的内存段.
     *
     * @param byteSize      分配大小 (字节)
     * @param byteAlignment 对齐字节数 (必须是 2 的幂)
     * @return 分配的 {@link MemorySegment}
     */
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        return arena.allocate(byteSize, byteAlignment);
    }
    
    /**
     * 分配一个 int 类型的数值.
     * @param value 初始值
     * @return 存储该值的 MemorySegment
     */
    public MemorySegment allocateInt(int value) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT);
        segment.set(ValueLayout.JAVA_INT, 0, value);
        return segment;
    }

    /**
     * 关闭内存区域，释放所有分配的堆外内存.
     * 调用此方法后，任何从此 Arena 分配的 MemorySegment 将不可用.
     */
    @Override
    public void close() {
        arena.close();
    }
    
    /**
     * 获取底层的 JDK Arena 实例.
     * 仅供通过底层 API (如 io_uring) 交互时使用.
     *
     * @return {@link Arena}
     */
    public Arena raw() {
        return arena;
    }
}
