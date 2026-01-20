package com.barrage.engine.simulate.context;

import com.barrage.engine.simulate.pool.NetworkInfrastructure;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 仿真上下文 (Zero-Copy Core)
 * <p>
 * 职责：
 * 1. 作为 "视口" (View) 绑定到当前虚拟用户的 Slot 内存上。
 * 2. 提供对 Request Buffer (用户写) 和 Response Buffer (IO 线程写指针) 的访问。
 * 3. 桥接 NetworkInfrastructure 能力。
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

    public SimulationContext() {}

    /**
     * 上下文切换 (Context Switch)
     * <p>
     * 当虚拟线程被调度去处理某个用户时，调用此方法将 Context "对准" 该用户的内存槽位。
     * 这是一个极低开销的操作 (两个赋值)。
     *
     * @param memoryBlock UserGroup 的共享大内存
     * @param userIndex   目标用户索引
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
     * 获取响应数据的长度
     * <p>
     * 数据来源：IO 线程在 handleCqe 时写入到 UserSlot Metadata 区域。
     */
    public long getDataLength() {
        return memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_LEN);
    }

    /**
     * 获取响应数据的 Buffer (Zero-Copy View)
     * <p>
     * 原理：
     * 1. 读取 IO 线程写入的物理内存地址 (Address) 和长度 (Length)。
     * 2. 使用 FFM API 基于该地址构建一个新的 MemorySegment 视图。
     * <p>
     * ⚠️ 警告 (生命周期安全)：
     * 返回的 Segment 直接指向 NetworkInfrastructure 内部的 RingBuffer。
     * 必须在当前 Node 逻辑执行期间（即虚拟线程让出 CPU 之前）完成读取或解析。
     * 一旦虚拟线程挂起或结束，IO 线程可能会覆盖这块内存。
     */
    public MemorySegment getResponseBuffer() {
        // 1. 读取元数据：IO 线程把数据放在了哪？
        long addr = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_PTR);
        long len  = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_RESP_LEN);

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
     * 获取请求缓冲区 (Request Buffer)
     * <p>
     * 用途：
     * HttpNode 或 TemplateManager 将组装好的 HTTP 请求报文写入这里。
     * 这块内存是分配在 UserSlot 内部的，属于用户私有，安全可靠。
     */
    public MemorySegment getRequestBuffer() {
        return memoryBlock.asSlice(currentSlotOffset + UserSlotLayout.OFFSET_REQ_BUFFER, UserSlotLayout.REQ_CAPACITY);
    }

    /**
     * 获取请求数据的数据视图 (用于发送)
     * <p>
     * 根据之前 setDataReference 设置的指针和长度，返回实际有效的数据切片。
     */
    public MemorySegment getDataAsSegment() {
        long addr = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_PTR);
        long len  = memoryBlock.get(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_LEN);

        if (len <= 0) return MemorySegment.NULL;

        // 这里假设 setDataReference 存的是绝对地址 (基于 memoryBlock 的 slice 或 ofAddress)
        // 在目前的实现中，通常是调用 getRequestBuffer() 写入数据后，
        // 再把 Buffer 的地址传给 setDataReference。
        // 为了简化，如果 addr 是相对 slot 的偏移：
        // return memoryBlock.asSlice(currentSlotOffset + addr, len);

        // 但为了通用性，我们假设它是一个通过 MemorySegment.address() 获得的绝对地址：
        return MemorySegment.ofAddress(addr).reinterpret(len);
    }

    /**
     * 设置请求数据的引用 (Pointer)
     * <p>
     * 告诉 Context："我把请求数据写在地址 X，长度为 Y，请在这个地址发包"。
     */
    public void setDataReference(long address, long length) {
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_PTR, address);
        memoryBlock.set(ValueLayout.JAVA_LONG, currentSlotOffset + UserSlotLayout.OFFSET_DATA_LEN, length);
    }

    /**
     * [Critical] 获取 Slot 的元数据区域
     * <p>
     * 用途：
     * 传给 IO 线程，让 IO 线程知道往哪里回写 "Response Address" 和 "Response Length"。
     * IO 线程会直接操作这块内存。
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