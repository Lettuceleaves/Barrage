package com.barrage.engine.simulate.context;

import com.barrage.engine.simulate.pool.NetworkInfrastructure;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 仿真运行上下文 (Zero-Copy Core).
 * <p>
 * 这是每个虚拟用户 (Agent) 在运行时持有的核心对象。
 * <p>
 * 核心设计职责：
 * <ol>
 * <li><b>视口 (View)：</b> 它并不直接持有数据，而是作为"游标"指向 {@link UserGroupContext}
 * 中当前用户对应的内存槽位 (Slot)。</li>
 * <li><b>零拷贝 IO 桥接：</b> 提供对 Request/Response Buffer 的访问。IO 线程直接将内核数据的地址写入
 * Slot，Context 负责将其包装为 {@link MemorySegment} 供 Node 使用。</li>
 * <li><b>基础设施访问：</b> 持有 {@link NetworkInfrastructure} 的引用，使 Node 能够发起 IO
 * 请求。</li>
 * </ol>
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public class SimulationContext {

    // ==========================================
    // 1. 核心状态
    // ==========================================

    // 当前 UserGroup 的大内存块 (Base Segment)
    private MemorySegment memoryBlock;

    // 当前用户在 memoryBlock 中的起始偏移量
    private long currentSlotOffset;

    // 基础设施引用 (用于 Node 发起 IO)
    private NetworkInfrastructure networkInfra;

    public SimulationContext() {
    }

    /**
     * 上下文切换 (Context Switch)。
     * <p>
     * 当虚拟线程被调度去处理某个用户时，必须先调用此方法，将 Context "对准" 该用户的内存槽位。
     * 这是一个极低开销的操作 (指针计算 + 赋值)，是实现 Thread-Per-Core 模拟百万用户的关键。
     *
     * @param memoryBlock UserGroup 的共享大内存段 (Base Address)
     * @param userIndex   目标用户在组内的索引
     */
    public void wrap(MemorySegment memoryBlock, int userIndex) {
        this.memoryBlock = memoryBlock;
        // 计算当前用户的绝对偏移量
        this.currentSlotOffset = (long) userIndex * UserSlotLayout.SLOT_SIZE;
    }

    // ==========================================
    // 2. 基础设施绑定
    // ==========================================

    public void setNetworkInfrastructure(NetworkInfrastructure networkInfra) {
        this.networkInfra = networkInfra;
    }

    public NetworkInfrastructure getNetworkInfrastructure() {
        return this.networkInfra;
    }

    // ==========================================
    // 3. Zero-Copy 核心逻辑 (Response Handling)
    // ==========================================

    /**
     * 获取响应数据的长度。
     * <p>
     * 数据来源：底层 IO 线程在完成 Read 操作后，会将实际读取的字节数写入 UserSlot 的 Metadata 区域。
     *
     * @return 响应数据字节数
     */
    public long getDataLength() {
        return memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_LEN);
    }

    /**
     * 获取响应数据的缓冲区视图 (Zero-Copy View)。
     * <p>
     * <b>原理：</b>
     * <ol>
     * <li>读取 IO 线程写入的物理内存地址 (Address) 和长度 (Length)。</li>
     * <li>使用 FFM API ({@link MemorySegment#ofAddress}) 基于该地址构建一个新的 MemorySegment
     * 视图。</li>
     * </ol>
     * <p>
     * <b>⚠️ 安全警告 (生命周期)：</b>
     * 返回的 Segment 直接指向 {@link NetworkInfrastructure} 内部的 RingBuffer。
     * <b>必须</b> 在当前 Node 逻辑执行期间（即虚拟线程让出 CPU 之前）完成读取或解析。
     * 一旦虚拟线程挂起或结束当前 tick，IO 线程可能会在后续循环中覆盖这块内存。
     *
     * @return 响应数据的内存段，如果无数据或异常则返回 {@link MemorySegment#NULL}
     */
    public MemorySegment getResponseBuffer() {
        // 1. 读取元数据：IO 线程把数据放在了哪？
        long addr = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_PTR);
        long len = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_LEN);

        // 防御性检查
        if (len <= 0 || addr == 0) {
            return MemorySegment.NULL;
        }

        // 2. 重构视图 (Reinterpret Pointer)
        // MemorySegment.ofAddress 创建的是一个大小为 0 的非受限 Segment
        // reinterpret(len) 赋予它边界，使其可以安全访问
        return MemorySegment.ofAddress(addr).reinterpret(len);
    }

    /**
     * [Internal] 提供给 NetworkInfrastructure 使用：写入 Response 指针
     * (虽然目前的架构是 IO 线程直接写内存，保留此方法可用于测试或 Mock)
     */
    public void setResponseReference(long address, long length) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_PTR, address);
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_LEN, length);
    }

    // ==========================================
    // 4. 请求构建逻辑 (Request Handling)
    // ==========================================

    /**
     * 获取请求缓冲区 (Request Buffer)。
     * <p>
     * <b>用途：</b>
     * 用于存放即将发送的 HTTP 请求报文。{@link com.barrage.engine.simulate.node.HttpNode} 或模板管理器
     * 会将组装好的数据写入这块区域。这块内存是分配在 UserSlot 内部的，属于用户私有，线程安全。
     *
     * @return 请求缓冲区的内存切片
     */
    public MemorySegment getRequestBuffer() {
        return memoryBlock.asSlice(currentSlotOffset + UserSlotLayout.OFFSET_REQ_BUFFER, UserSlotLayout.REQ_CAPACITY);
    }

    /**
     * 获取准备发送的数据视图。
     * <p>
     * 根据之前 {@link #setDataReference(long, long)} 设置的指针和长度，返回实际有效的数据切片。
     * 供 IO 线程读取以进行发送操作。
     *
     * @return 待发送数据的内存段
     */
    public MemorySegment getDataAsSegment() {
        long addr = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_PTR);
        long len = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_LEN);

        if (len <= 0)
            return MemorySegment.NULL;

        // 这里假设 setDataReference 存的是绝对地址 (基于 memoryBlock 的 slice 或 ofAddress)
        // 在目前的实现中，通常是调用 getRequestBuffer() 写入数据后，
        // 再把 Buffer 的地址传给 setDataReference。
        // 为了简化，如果 addr 是相对 slot 的偏移：
        // return memoryBlock.asSlice(currentSlotOffset + addr, len);

        // 但为了通用性，我们假设它是一个通过 MemorySegment.address() 获得的绝对地址：
        return MemorySegment.ofAddress(addr).reinterpret(len);
    }

    /**
     * 设置请求数据的引用 (Pointer)。
     * <p>
     * 告诉 Context："我已把请求数据写在地址 Address，长度为 Length，请记录下来"。
     * IO 线程后续会读取这个记录来发起 send 系统调用。
     *
     * @param address 数据所在的绝对物理地址
     * @param length  数据长度
     */
    public void setDataReference(long address, long length) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_PTR, address);
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_LEN, length);
    }

    /**
     * [Critical] 获取当前 Slot 的元数据区域切片。
     * <p>
     * <b>用途：</b>
     * 将这块内存暴露给底层 IO 线程。IO 线程在处理完 Read 事件后，会直接往这块内存的
     * 特定偏移 (OFFSET_RESP_PTR, OFFSET_RESP_LEN) 写入数据。
     * 实现了 User 线程与 IO 线程的高效交互。
     *
     * @return Slot 元数据区的内存段
     */
    public MemorySegment getSlotMetadataSegment() {
        // 返回整个 Slot 的切片，NetworkInfrastructure 知道具体的偏移量 (OFFSET_RESP_PTR 等)
        return memoryBlock.asSlice(currentSlotOffset, UserSlotLayout.SLOT_SIZE);
    }

    // ==========================================
    // 5. 元数据访问 (Metadata Accessors)
    // ==========================================

    public void setUserId(long userId) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_USER_ID, userId);
    }

    public long getUserId() {
        return memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_USER_ID);
    }

    public void setNextTransitionIndex(long index) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_NEXT_INDEX, index);
    }

    public long getNextTransitionIndex() {
        return memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_NEXT_INDEX);
    }

    public void setStatus(long status) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_STATUS, status);
    }

    public long getStatus() {
        return memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_STATUS);
    }
}