package com.barrage.kernel.memory;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * 用于统一管理堆外内存的 DirectHeap 接口。
 *
 * <p>实现类负责：</p>
 * <ul>
 *   <li>分配内存块（返回 {@link MemorySegment}）</li>
 *   <li>释放已分配的块</li>
 *   <li>一次性分配多个块</li>
 *   <li>限制总内存使用量</li>
 *   <li>支持零分配的原始地址获取</li>
 * </ul>
 *
 * <p>此接口定义了可以使用 JDK 25 {@code Arena}、Unsafe 或其他原生内存库实现的行为。</p>
 */
public interface DirectHeap {

    /**
     * 分配指定大小的内存块。
     *
     * @param size 要分配的总字节数。
     * @return 代表已分配内存的 {@link MemorySegment}。
     */
    MemorySegment allocate(long size);

    /**
     * 分配内存块并返回其原始起始地址。
     * 这旨在用于高性能路径，以避免创建 {@link MemorySegment} 对象。
     *
     * @param size 要分配的总字节数。
     * @return 原始内存地址 (long)。
     */
    long allocateAddress(long size);

    /**
     * 释放之前分配的内存段。
     *
     * @param segment 要释放的 {@link MemorySegment}。
     */
    void free(MemorySegment segment);

    /**
     * 在单个操作中分配多个相同大小的块。
     *
     * @param blockSize 每个单独块的大小（以字节为单位）。
     * @param blockCount 要分配的块数。
     * @return 已分配 {@link MemorySegment} 的列表。
     */
    List<MemorySegment> allocateBlocks(long blockSize, int blockCount);

    /**
     * 返回此堆当前分配的内存总额。
     *
     * @return 总分配字节数。
     */
    long getTotalAllocated();

    /**
     * 设置总内存分配的最大限制。
     * 如果超过此限制，实现类应抛出异常。
     *
     * @param maxTotalSize 允许的最大字节数。
     */
    void setMaxTotalSize(long maxTotalSize);

    /**
     * 释放内存段列表。
     *
     * @param segments 要释放的 {@link MemorySegment} 列表。
     */
    void free(List<MemorySegment> segments);
}
