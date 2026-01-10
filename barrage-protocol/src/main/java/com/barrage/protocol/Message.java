package com.barrage.protocol;

import java.lang.foreign.MemorySegment;

/**
 * 网络协议消息的抽象基类。
 * <p>
 * 该类本质上是对 Java FFM {@link MemorySegment} 的轻量级封装。
 * 它的设计目的是为不同的上层应用协议（如 HTTP, Custom TCP）提供统一的数据载体接口，
 * 使得底层 {@code ClientEngine} 和 {@code ServerEngine} 能够以统一的方式
 * 获取原生内存地址并执行 Zero-Copy 发送，而无需关心具体的协议内容。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 * @see com.barrage.protocol.HTTP.HttpMessage
 */
public abstract class Message {

    /**
     * 承载消息内容的底层内存段。
     * <p>
     * 通常指向堆外内存 (Off-Heap Memory) 或全局常量内存区 (Global Arena)。
     * 声明为 {@code final} 确保消息一旦创建，其底层数据源不可变（Immutable），从而保证线程安全。
     */
    protected final MemorySegment segment;

    /**
     * 构造一个基于特定内存段的消息实例。
     *
     * @param segment 包含完整消息数据的原生内存段
     */
    protected Message(MemorySegment segment) {
        this.segment = segment;
    }

    /**
     * 获取底层的内存段对象。
     * <p>
     * 网络引擎将调用此方法获取内存段，进而提取其物理地址 (Address) 以填充 io_uring 的 SQE (Submission Queue Entry)。
     *
     * @return 原生内存段
     */
    public MemorySegment segment() {
        return segment;
    }

    /**
     * 获取消息数据的字节长度。
     * <p>
     * 实际上是调用 {@link MemorySegment#byteSize()} 并转型为 {@code int}。
     * 在单条网络消息场景下，长度通常远小于 2GB，因此转型是安全的。
     *
     * @return 消息长度 (字节)
     */
    public int length() {
        return (int) segment.byteSize();
    }
}