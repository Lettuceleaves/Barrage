package com.barrage.kernel.io;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * 原生常量、内存结构和布局定义的中心仓库。
 * <p>
 * 此类充当 Java 堆空间与 Linux 内核空间之间的“字典”，包含了通过 JDK FFM API (Project Panama)
 * 与 {@code io_uring} 接口和标准 C 库 (libc) 交互所需的所有底层定义。
 *
 * <h2>主要功能：</h2>
 * <ul>
 * <li><b>操作码定义：</b> 映射 Linux 内核 {@code io_uring_op} 枚举。</li>
 * <li><b>内存布局 (Layouts)：</b> 精确复刻 C 语言结构体（如 {@code io_uring_sqe}, {@code io_uring_cqe}）在内存中的二进制排列。</li>
 * <li><b>高性能偏移量：</b> 预先计算字段偏移量，避免在热点代码路径（Hot Path）中重复计算布局。</li>
 * <li><b>系统调用常量：</b> 包含 x86_64 架构下的系统调用号及 {@code mmap} 魔数。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class NativeConstants {

    // =================================================================================
    // IoUring Opcodes (操作码)
    // 对应 Linux 内核头文件 <liburing.h> 或 <linux/io_uring.h> 中的定义
    // =================================================================================

    /** 空操作，仅用于测试或占位 */
    public static final byte IORING_OP_NOP = 0;
    /** 向量化读 (preadv) */
    public static final byte IORING_OP_READV = 1;
    /** 向量化写 (pwritev) */
    public static final byte IORING_OP_WRITEV = 2;
    /** 文件同步 (fsync) */
    public static final byte IORING_OP_FSYNC = 3;
    /** 使用已注册缓冲区的读操作 */
    public static final byte IORING_OP_READ_FIXED = 4;
    /** 使用已注册缓冲区的写操作 */
    public static final byte IORING_OP_WRITE_FIXED = 5;
    /** 添加轮询 (Poll) */
    public static final byte IORING_OP_POLL_ADD = 6;
    /** 移除轮询 */
    public static final byte IORING_OP_POLL_REMOVE = 7;
    /** 同步文件范围 */
    public static final byte IORING_OP_SYNC_FILE_RANGE = 8;
    /** 发送消息 (sendmsg) */
    public static final byte IORING_OP_SENDMSG = 9;
    /** 接收消息 (recvmsg) */
    public static final byte IORING_OP_RECVMSG = 10;
    /** 超时设置 */
    public static final byte IORING_OP_TIMEOUT = 11;
    /** 移除超时 */
    public static final byte IORING_OP_TIMEOUT_REMOVE = 12;
    /** 接受连接 (accept4) */
    public static final byte IORING_OP_ACCEPT = 13;
    /** 异步取消操作 */
    public static final byte IORING_OP_ASYNC_CANCEL = 14;
    /** 链接超时 */
    public static final byte IORING_OP_LINK_TIMEOUT = 15;
    /** 发起连接 (connect) */
    public static final byte IORING_OP_CONNECT = 16;
    /** 文件预分配 (fallocate) */
    public static final byte IORING_OP_FALLOCATE = 17;
    /** 打开文件 (openat) */
    public static final byte IORING_OP_OPENAT = 18;
    /** 关闭文件描述符 (close) */
    public static final byte IORING_OP_CLOSE = 19;
    /** 批量更新文件描述符 */
    public static final byte IORING_OP_FILES_UPDATE = 20;
    /** 获取文件状态 (statx) */
    public static final byte IORING_OP_STATX = 21;
    /** 普通读 (read) */
    public static final byte IORING_OP_READ = 22;
    /** 普通写 (write) */
    public static final byte IORING_OP_WRITE = 23;
    /** 文件建议 (fadvise) */
    public static final byte IORING_OP_FADVISE = 24;
    /** 内存建议 (madvise) */
    public static final byte IORING_OP_MADVISE = 25;
    /** 发送数据 (send) */
    public static final byte IORING_OP_SEND = 26;
    /** 接收数据 (recv) */
    public static final byte IORING_OP_RECV = 27;
    /** 高级打开文件 (openat2) */
    public static final byte IORING_OP_OPENAT2 = 28;
    /** epoll 控制操作 */
    public static final byte IORING_OP_EPOLL_CTL = 29;
    /** 零拷贝发送 (send_zc) */
    public static final byte IORING_OP_SEND_ZC = 45;
    /** 零拷贝接收 (recv_zc) */
    public static final byte IORING_OP_RECV_ZC = 50;

    // =================================================================================
    // Memory Layouts (内存布局)
    // 使用 FFM API 描述 C 结构体的内存对齐方式
    // =================================================================================

    /**
     * {@code io_uring_sqe} (Submission Queue Entry) 结构体布局。
     * <p>
     * 大小：64 字节。该结构体是 io_uring 交互的核心，包含了执行 I/O 操作所需的所有参数。
     */
    public static final StructLayout SQE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("opcode"),       // 操作码
            ValueLayout.JAVA_BYTE.withName("flags"),        // 标志位 (如 IOSQE_IO_LINK)
            ValueLayout.JAVA_SHORT.withName("ioprio"),      // IO 优先级
            ValueLayout.JAVA_INT.withName("fd"),            // 文件描述符
            ValueLayout.JAVA_LONG.withName("off"),          // 偏移量
            ValueLayout.JAVA_LONG.withName("addr"),         // 缓冲区地址
            ValueLayout.JAVA_INT.withName("len"),           // 缓冲区长度
            ValueLayout.JAVA_INT.withName("rw_flags"),      // 读写标志
            ValueLayout.JAVA_LONG.withName("user_data"),    // 用户数据 (回传给 CQE)
            ValueLayout.JAVA_SHORT.withName("buf_index"),   // 注册缓冲区索引
            ValueLayout.JAVA_SHORT.withName("personality"), // 个性化凭证
            ValueLayout.JAVA_INT.withName("splice_fd_in"),  // splice 源 fd
            ValueLayout.JAVA_LONG.withName("addr3"),        // 额外地址字段
            ValueLayout.JAVA_LONG.withName("pad2")          // 填充位，保证 64 字节对齐
    );

    /**
     * {@code io_uring_cqe} (Completion Queue Entry) 结构体布局。
     * <p>
     * 大小：16 字节。用于内核向用户态返回 I/O 操作的结果。
     */
    public static final StructLayout CQE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("user_data"),    // 对应 SQE 传入的 user_data
            ValueLayout.JAVA_INT.withName("res"),           // 操作结果 (返回值或错误码)
            ValueLayout.JAVA_INT.withName("flags")          // 完成标志
    );

    // =================================================================================
    // Static Offsets (静态偏移量)
    // 预先计算字段在结构体中的字节偏移，用于 VarHandle 高效访问
    // =================================================================================

    public static final long SQE_OFF_OPCODE = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("opcode"));
    public static final long SQE_OFF_FLAGS = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags"));
    public static final long SQE_OFF_FD = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("fd"));
    public static final long SQE_OFF_ADDR = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("addr"));
    public static final long SQE_OFF_LEN = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("len"));
    public static final long SQE_OFF_RW_FLAGS = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("rw_flags"));
    public static final long SQE_OFF_USER_DATA = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("user_data"));

    public static final long CQE_OFF_RES = CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("res"));
    public static final long CQE_OFF_USER_DATA = CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("user_data"));

    // =================================================================================
    // System Calls & Magic Numbers (系统调用与魔数)
    // =================================================================================

    /** 系统调用号：io_uring_setup (x86_64) */
    public static final int SYS_io_uring_setup = 425;
    /** 系统调用号：io_uring_enter (x86_64) */
    public static final int SYS_io_uring_enter = 426;
    /** 系统调用号：io_uring_register (x86_64) */
    public static final int SYS_io_uring_register = 427;

    /** mmap 偏移量：映射提交队列 (SQ) */
    public static final long IORING_OFF_SQ_RING = 0L;
    /** mmap 偏移量：映射完成队列 (CQ) */
    public static final long IORING_OFF_CQ_RING = 0x8000000L;
    /** mmap 偏移量：映射 SQE 数组区域 */
    public static final long IORING_OFF_SQES = 0x10000000L;

    // =================================================================================
    // Ring Setup Structures (环形队列配置结构)
    // =================================================================================

    /**
     * {@code io_sqring_offsets} 结构布局。
     * <p>
     * 描述了提交队列各指针在 mmap 内存块中的相对位置。
     */
    public static final StructLayout SQ_RING_OFFSETS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("head"),
            ValueLayout.JAVA_INT.withName("tail"),
            ValueLayout.JAVA_INT.withName("ring_mask"),
            ValueLayout.JAVA_INT.withName("ring_entries"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("dropped"),
            ValueLayout.JAVA_INT.withName("array"),
            ValueLayout.JAVA_INT.withName("resv1"),
            ValueLayout.JAVA_LONG.withName("resv2")
    );

    /**
     * {@code io_cqring_offsets} 结构布局。
     * <p>
     * 描述了完成队列各指针在 mmap 内存块中的相对位置。
     */
    public static final StructLayout CQ_RING_OFFSETS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("head"),
            ValueLayout.JAVA_INT.withName("tail"),
            ValueLayout.JAVA_INT.withName("ring_mask"),
            ValueLayout.JAVA_INT.withName("ring_entries"),
            ValueLayout.JAVA_INT.withName("overflow"),
            ValueLayout.JAVA_INT.withName("cqes"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("resv1"),
            ValueLayout.JAVA_LONG.withName("resv2")
    );

    /**
     * {@code io_uring_params} 结构布局。
     * <p>
     * 用于 {@code io_uring_setup} 调用，作为入参配置队列大小，作为出参获取内核返回的偏移量信息。
     */
    public static final StructLayout IO_URING_PARAMS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("sq_entries"),
            ValueLayout.JAVA_INT.withName("cq_entries"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("sq_thread_cpu"),
            ValueLayout.JAVA_INT.withName("sq_thread_idle"),
            ValueLayout.JAVA_INT.withName("features"),
            ValueLayout.JAVA_INT.withName("wq_fd"),
            ValueLayout.JAVA_INT.withName("resv0"),
            ValueLayout.JAVA_INT.withName("resv1"),
            ValueLayout.JAVA_INT.withName("resv2"),
            SQ_RING_OFFSETS_LAYOUT.withName("sq_off"),
            CQ_RING_OFFSETS_LAYOUT.withName("cq_off")
    );

    // --- Setup Flags ---
    /** 启用内核侧轮询 (Kernel Side Polling)，减少系统调用 */
    public static final int IORING_SETUP_SQPOLL = 1 << 1;
    /** 绑定 SQ 线程到特定 CPU */
    public static final int IORING_SETUP_SQ_AFF = 1 << 2;
    /** 自定义 CQ 大小 */
    public static final int IORING_SETUP_CQSIZE = 1 << 3;
    /** 限制 SQ 大小 */
    public static final int IORING_SETUP_CLAMP = 1 << 4;
    /** 共享 Work Queue */
    public static final int IORING_SETUP_ATTACH_WQ = 1 << 5;
    /** 初始创建时禁用 Ring */
    public static final int IORING_SETUP_R_DISABLED = 1 << 6;

    // --- Register Opcodes ---
    public static final int IORING_REGISTER_BUFFERS = 0;
    public static final int IORING_UNREGISTER_BUFFERS = 1;

    // --- Ring Flags ---
    public static final int IORING_SQ_NEED_WAKEUP = 1 << 0;

    // --- Enter Flags ---
    /** 等待指定数量的事件完成 */
    public static final int IORING_ENTER_GETEVENTS = 1 << 0;
    public static final int IORING_ENTER_SQ_WAKEUP = 1 << 1;
    public static final int IORING_ENTER_SQ_WAIT = 1 << 2;
    public static final int IORING_ENTER_EXT_ARG = 1 << 3;
    public static final int IORING_ENTER_REGISTERED_RING = 1 << 4;

    // --- IO Flags ---
    /** 使用固定缓冲区 (Fixed Buffer) */
    public static final int IORING_RECVSEND_FIXED_BUF = 1 << 0;

    // =================================================================================
    // MMAP Constants (内存映射常量)
    // =================================================================================
    public static final int PROT_READ = 0x1;
    public static final int PROT_WRITE = 0x2;
    public static final int MAP_SHARED = 0x01;
    /** 预先填充页表，减少缺页中断 */
    public static final int MAP_POPULATE = 0x08000;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final long MAP_FAILED = -1L;

    // =================================================================================
    // Socket & Network Constants (网络常量)
    // =================================================================================
    public static final int AF_UNIX = 1;
    public static final int AF_INET = 2;
    public static final int SOCK_STREAM = 1;
    public static final int IPPROTO_TCP = 6;

    // fcntl
    public static final int F_GETFL = 3;
    public static final int F_SETFL = 4;
    public static final int O_NONBLOCK = 04000;

    /**
     * {@code sockaddr_in} (IPv4 地址结构) 布局。
     */
    public static final StructLayout SOCKADDR_IN_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("sin_family"), // 地址族 (AF_INET)
            ValueLayout.JAVA_SHORT.withName("sin_port"),   // 端口 (大端序)
            ValueLayout.JAVA_INT.withName("sin_addr"),     // IP 地址 (大端序)
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("sin_zero") // 填充字节
    );
}