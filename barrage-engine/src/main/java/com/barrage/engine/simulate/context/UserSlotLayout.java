package com.barrage.engine.simulate.context;

/**
 * 用户槽位内存布局定义 (Zero-Copy RingBuffer Edition)
 * <p>
 * 核心设计变更：
 * 1. 移除 RESPONSE_BUFFER (4KB)，改为存储指针 (16 Bytes)。
 * 2. 保留 REQUEST_BUFFER (896 Bytes)，因为发送数据通常需要用户组装。
 * 3. 总大小压缩至 1024 字节，大幅提升 CPU 缓存命中率。
 * <p>
 * Layout Diagram:
 * [0   - 64)    : Metadata (UID, Status, NextIndex, ReqPtr, ReqLen...)
 * [64  - 128)   : Response References (Address + Length) <-- IO 线程写这里
 * [128 - 1024)  : Request Buffer (User 写这里)
 * ---------------------------------------------------------------------
 * Total Size    : 1024 bytes
 */
public class UserSlotLayout {

    // ==========================================
    // 1. Metadata Area (0 - 64 bytes)
    // ==========================================
    // 用户 ID
    public static final long OFFSET_USER_ID     = 0;  // long (8)

    // 下一跳节点索引
    public static final long OFFSET_NEXT_INDEX  = 8;  // long (8)

    // 状态码 (200, 404, etc.)
    public static final long OFFSET_STATUS      = 16; // long (8)

    // 发送数据指针 (Request Pointer)
    // 指向 OFFSET_REQ_BUFFER 或者 TemplateManager 预编译的静态内存区
    public static final long OFFSET_DATA_PTR    = 24; // long (8) (原 OFFSET_REQ_PTR)

    // 发送数据长度
    public static final long OFFSET_DATA_LEN    = 32; // long (8) (原 OFFSET_REQ_LEN)

    // (保留 40-64 用于对齐或未来扩展)

    // ==========================================
    // 2. Response Reference Area (64 - 128 bytes)
    // ==========================================
    // [Zero-Copy Core]
    // 这里不再存放实际数据，只存放 IoLane RingBuffer 的 "物理地址"
    public static final long OFFSET_RESP_PTR    = 64; // long (8)

    // 响应数据的实际长度
    public static final long OFFSET_RESP_LEN    = 72; // long (8)

    // (保留 80-128 用于对齐)

    // ==========================================
    // 3. Request Buffer Area (128 - 1024 bytes)
    // ==========================================
    // 用于存放 HttpNode 组装好的请求报文
    public static final long OFFSET_REQ_BUFFER  = 128;

    // 容量 896 字节，足够容纳大多数 GET/POST 请求头
    public static final long REQ_CAPACITY       = 896;

    // ==========================================
    // Total Slot Size
    // ==========================================
    public static final long SLOT_SIZE          = 1024;
}