package com.barrage.kernel.memory;


import com.barrage.kernel.config.basic.BasicConfig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 基于 FFM API 的高性能定长内存池管理器 (Slab Allocator)。
 * <p>
 * 该类是为了满足 "Zero-GC" 和 "Cache Locality" (缓存局部性) 需求而设计的。
 * 它在堆外内存中申请一块连续的大内存块 (Slab)，并将其逻辑划分为多个固定大小的 Slot。
 * </p>
 * <h2>内存布局 (Slot Layout)</h2>
 * 每个 Slot 包含头部元数据和数据缓冲区，紧凑排列以减少 CPU 缓存未命中：
 * <pre>{@code
 * |<--- 4B --->|<--- 4B --->|<------- READ_SZ (e.g. 1KB) ------->|
 * +------------+------------+------------------------------------+
 * |     FD     |    TYPE    |          DATA BUFFER               |
 * +------------+------------+------------------------------------+
 * ^ Offset 0   ^ Offset 4   ^ Offset 8
 * }</pre>
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>O(1) 分配与释放：</b> 使用简单的 int 数组模拟栈结构管理空闲索引，无扫描开销。</li>
 * <li><b>Zero-GC：</b> 所有的 {@link MemorySegment} 切片对象在构造时预先创建并缓存。
 * 在高频 IO 读写过程中，不会创建任何新的 Java 对象。</li>
 * <li><b>非线程安全：</b> 专为 Thread-Per-Core 模型设计，每个 Worker 线程拥有独立的 Arena 实例，无需加锁。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class MemoryArena {

    // --- 内存偏移量常量 ---
    /** 文件描述符 (FD) 的偏移量：0 */
    private static final long OFF_FD = 0;
    /** 事件类型 (TYPE) 的偏移量：4 */
    private static final long OFF_TYPE = 4;
    /** 数据缓冲区 (BUFFER) 的起始偏移量：8 */
    private static final long OFF_BUFFER = 8;
    /** 单个 Slot 的总字节大小 */
    private static final long SLOT_SIZE = 4 + 4 + BasicConfig.getREAD_SZ();

    /** * 核心内存块 (Slab)。
     * 所有的 Slot 都位于这块连续的堆外内存上。
     */
    private final MemorySegment slab;

    /**
     * 预分配的 Buffer 切片缓存。
     * <p>
     * 为了避免在 {@link #getBuffer(int)} 时重复调用 {@link MemorySegment#asSlice} (这会产生新的 Java 对象)，
     * 我们在初始化阶段就将所有 Slot 的 Buffer 部分切分好并存储在此数组中。
     */
    private final MemorySegment[] cachedBuffers;

    /** * 基于数组实现的空闲索引栈。
     * 存储当前可用的 Slot 索引。
     */
    private final int[] freeIndices;

    /** 栈顶指针，指向 freeIndices 中下一个可用位置 */
    private int top;

    /**
     * 构造并初始化内存池。
     *
     * @param arena    用于分配堆外内存的范围作用域 (Scope)
     * @param capacity 内存池容量 (Slot 数量)
     */
    public MemoryArena(Arena arena, int capacity) {
        long totalSize = capacity * SLOT_SIZE;
        // 分配连续的大内存块
        // 128字节对齐：为了适配常见的 CPU Cache Line (通常 64 字节)，防止伪共享并优化预取
        this.slab = arena.allocate(totalSize, 128);

        this.freeIndices = new int[capacity];
        this.cachedBuffers = new MemorySegment[capacity];

        // 预热：初始化空闲栈并预先切片
        for (int i = 0; i < capacity; i++) {
            freeIndices[i] = i; // 初始状态所有索引皆空闲

            // 预先创建好 buffer 部分的 slice，供 io_uring read/write 使用
            // 这个 slice 对象会被 JVM 堆缓存，生命周期内一直复用，实现 Zero-GC
            this.cachedBuffers[i] = slab.asSlice(i * SLOT_SIZE + OFF_BUFFER, BasicConfig.getREAD_SZ());
        }
        this.top = capacity;
    }

    /**
     * 分配一个空闲 Slot。
     *
     * @return 分配到的 Slot 索引 (0 ~ capacity-1)
     * @throws RuntimeException 如果内存池已耗尽 (OOM)
     */
    public int allocate() {
        if (top == 0) throw new RuntimeException("MemoryArena OOM: No free slots");
        return freeIndices[--top];
    }

    /**
     * 释放一个 Slot，将其归还给内存池。
     *
     * @param index 要释放的 Slot 索引
     */
    public void free(int index) {
        // 简单防溢出检查，高性能场景下通常假设调用者逻辑正确以省略此判断
        if (top == freeIndices.length) return;
        freeIndices[top++] = index;
    }

    /**
     * 获取指定索引对应的、用于 IO 操作的数据缓冲区切片。
     * <p>
     * 直接返回预缓存的 {@link MemorySegment} 对象，无对象分配开销。
     *
     * @param index Slot 索引
     * @return 能够直接传递给 io_uring 的 MemorySegment
     */
    public MemorySegment getBuffer(int index) {
        return cachedBuffers[index];
    }

    // --- 字段访问封装 (替代原先的 slab.set/get) ---

    /**
     * 同时写入事件类型和关联的文件描述符。
     * <p>
     * 通常在提交 SQE 之前调用，用于保存上下文信息，以便在 CQE 返回时恢复状态。
     *
     * @param index Slot 索引
     * @param type  事件类型 (如 READ, WRITE, ACCEPT)
     * @param fd    关联的文件描述符
     */
    public void setEventInfo(int index, int type, int fd) {
        long offset = index * SLOT_SIZE;
        slab.set(ValueLayout.JAVA_INT, offset + OFF_TYPE, type);
        slab.set(ValueLayout.JAVA_INT, offset + OFF_FD, fd);
    }

    /**
     * 仅写入文件描述符。
     * <p>
     * 通常用于连接建立阶段，或初始化 Slot 时。
     *
     * @param index Slot 索引
     * @param fd    文件描述符
     */
    public void setFd(int index, int fd) {
        long offset = index * SLOT_SIZE;
        slab.set(ValueLayout.JAVA_INT, offset + OFF_FD, fd);
    }

    /**
     * 仅写入事件类型。
     * <p>
     * 用于状态机流转，例如从 WRITE 状态切换到 READ 状态。
     *
     * @param index Slot 索引
     * @param type  新的事件类型
     */
    public void setType(int index, int type) {
        long offset = index * SLOT_SIZE;
        slab.set(ValueLayout.JAVA_INT, offset + OFF_TYPE, type);
    }

    /**
     * 读取 Slot 中保存的文件描述符。
     *
     * @param index Slot 索引
     * @return fd
     */
    public int getFd(int index) {
        long offset = index * SLOT_SIZE;
        return slab.get(ValueLayout.JAVA_INT, offset + OFF_FD);
    }

    /**
     * 读取 Slot 中保存的事件类型。
     *
     * @param index Slot 索引
     * @return type
     */
    public int getType(int index) {
        long offset = index * SLOT_SIZE;
        return slab.get(ValueLayout.JAVA_INT, offset + OFF_TYPE);
    }
}