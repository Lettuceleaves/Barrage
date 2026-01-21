package com.barrage.engine.simulate.context;

/**
 * 用户槽位内存布局定义 (Zero-Copy RingBuffer Edition).
 * <p>
 * 定义了每个虚拟用户 (User Slot) 占用的内存结构。
 * 总大小压缩至 1024 字节，以最大化 CPU 缓存命中率 (Cache Locality)。
 * <p>
 * <b>Layout Diagram:</b>
 * 
 * <pre>
 * [0   - 64)    : Metadata (UID, Status, NextIndex, ReqPtr, ReqLen...)
 * [64  - 128)   : Response References (Address + Length) <-- IO 线程写这里
 * [128 - 1024)  : Request Buffer (User 写这里)
 * ---------------------------------------------------------------------
 * Total Size    : 1024 bytes
 * </pre>
 */
public class UserSlotLayout {

    // ==========================================
    // 1. Metadata Area (0 - 64 bytes)
    // ==========================================
    // 用户 ID
    public static final long OFFSET_USER_ID = 0; // long (8)

    // 下一跳节点索引
    public static final long OFFSET_NEXT_INDEX = 8; // long (8)

    /** 状态码字段偏移 (200, 404, etc.) */
    public static final long OFFSET_STATUS = 16; // long (8)

    /** 发送数据指针偏移 (指向 OFFSET_REQ_BUFFER 或静态区) */
    public static final long OFFSET_DATA_PTR = 24; // long (8) (原 OFFSET_REQ_PTR)

    // 发送数据长度
    public static final long OFFSET_DATA_LEN = 32; // long (8) (原 OFFSET_REQ_LEN)

    // (保留 40-64 用于对齐或未来扩展)

    // ==========================================
    // 2. Response Reference Area (64 - 128 bytes)
    // ==========================================
    /** 响应数据地址偏移 (IO 线程写) - 存储物理地址 */
    public static final long OFFSET_RESP_PTR = 64; // long (8)

    // 响应数据的实际长度
    public static final long OFFSET_RESP_LEN = 72; // long (8)

    // (保留 80-128 用于对齐)

    // ==========================================
    // 3. Request Buffer Area (128 - 1024 bytes)
    // ==========================================
    /** 请求缓冲区起始偏移 (用于 HttpNode 写入数据) */
    public static final long OFFSET_REQ_BUFFER = 128;

    // 容量 896 字节，足够容纳大多数 GET/POST 请求头
    public static final long REQ_CAPACITY = 896;

    // ==========================================
    // Total Slot Size
    // ==========================================
    public static final long SLOT_SIZE = 1024;
}