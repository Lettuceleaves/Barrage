package com.barrage.kernel.io;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * 原生常量、结构和布局定义的中心仓库。
 * <p>
 * 此类包含通过 FFM API 与 Linux 内核的 {@code io_uring} 接口和标准 C 库 (libc) 交互所需的所有定义。
 * </p>
 */
public class NativeConstants {
    
    /**
     * io_uring 操作代码 (Opcodes)。
     */
    // io_uring opcodes
    public static final byte IORING_OP_NOP = 0;
    public static final byte IORING_OP_READV = 1;
    public static final byte IORING_OP_WRITEV = 2;
    public static final byte IORING_OP_FSYNC = 3;
    public static final byte IORING_OP_READ_FIXED = 4;
    public static final byte IORING_OP_WRITE_FIXED = 5;
    public static final byte IORING_OP_POLL_ADD = 6;
    public static final byte IORING_OP_POLL_REMOVE = 7;
    public static final byte IORING_OP_SYNC_FILE_RANGE = 8;
    public static final byte IORING_OP_SENDMSG = 9;
    public static final byte IORING_OP_RECVMSG = 10;
    public static final byte IORING_OP_TIMEOUT = 11;
    public static final byte IORING_OP_TIMEOUT_REMOVE = 12;
    public static final byte IORING_OP_ACCEPT = 13;
    public static final byte IORING_OP_ASYNC_CANCEL = 14;
    public static final byte IORING_OP_LINK_TIMEOUT = 15;
    public static final byte IORING_OP_CONNECT = 16;
    public static final byte IORING_OP_FALLOCATE = 17;
    public static final byte IORING_OP_OPENAT = 18;
    public static final byte IORING_OP_CLOSE = 19;
    public static final byte IORING_OP_FILES_UPDATE = 20;
    public static final byte IORING_OP_STATX = 21;
    public static final byte IORING_OP_READ = 22;
    public static final byte IORING_OP_WRITE = 23;
    public static final byte IORING_OP_FADVISE = 24;
    public static final byte IORING_OP_MADVISE = 25;
    public static final byte IORING_OP_SEND = 26;
    public static final byte IORING_OP_RECV = 27;
    public static final byte IORING_OP_OPENAT2 = 28;
    public static final byte IORING_OP_EPOLL_CTL = 29;
    public static final byte IORING_OP_SEND_ZC = 45;
    public static final byte IORING_OP_RECV_ZC = 50;

    /**
     * io_uring 结构的内存布局。
     */
    // Correct 64 layout
    public static final StructLayout SQE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("opcode"),
        ValueLayout.JAVA_BYTE.withName("flags"),
        ValueLayout.JAVA_SHORT.withName("ioprio"),
        ValueLayout.JAVA_INT.withName("fd"),
        ValueLayout.JAVA_LONG.withName("off"),
        ValueLayout.JAVA_LONG.withName("addr"),
        ValueLayout.JAVA_INT.withName("len"),
        ValueLayout.JAVA_INT.withName("rw_flags"),
        ValueLayout.JAVA_LONG.withName("user_data"),
        ValueLayout.JAVA_SHORT.withName("buf_index"),
        ValueLayout.JAVA_SHORT.withName("personality"),
        ValueLayout.JAVA_INT.withName("splice_fd_in"),
        ValueLayout.JAVA_LONG.withName("addr3"),
        ValueLayout.JAVA_LONG.withName("pad2")
    );

    public static final StructLayout CQE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("user_data"),
        ValueLayout.JAVA_INT.withName("res"),
        ValueLayout.JAVA_INT.withName("flags")
    );

    /**
     * 为 SQE 和 CQE 字段预先计算的静态偏移量，以避免在热循环中分配。
     */
    // Static offsets to avoid allocation in hot loop
    public static final long SQE_OFF_OPCODE = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("opcode"));
    public static final long SQE_OFF_FLAGS = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags"));
    public static final long SQE_OFF_FD = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("fd"));
    public static final long SQE_OFF_ADDR = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("addr"));
    public static final long SQE_OFF_LEN = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("len"));
    public static final long SQE_OFF_RW_FLAGS = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("rw_flags"));
    public static final long SQE_OFF_USER_DATA = SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("user_data"));

    public static final long CQE_OFF_RES = CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("res"));
    public static final long CQE_OFF_USER_DATA = CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("user_data"));

    /**
     * Linux 系统调用号 (x86_64 架构)。
     */
    // Syscalls (x86_64)
    public static final int SYS_io_uring_setup = 425;
    public static final int SYS_io_uring_enter = 426;
    public static final int SYS_io_uring_register = 427;

    /**
     * {@code mmap} 使用的 io_uring 专用内存偏移量。
     */
    // Magic offsets
    public static final long IORING_OFF_SQ_RING = 0L;
    public static final long IORING_OFF_CQ_RING = 0x8000000L;
    public static final long IORING_OFF_SQES = 0x10000000L;

    /**
     * 提交队列 (SQ) 环偏移量结构的布局。
     */
    // Struct io_sqring_offsets
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
     * 完成队列 (CQ) 环偏移量结构的布局。
     */
    // Struct io_cqring_offsets
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
     * 环设置过程中使用的 {@code io_uring_params} 结构布局。
     */
    // Struct io_uring_params
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

    public static final int IORING_SETUP_SQPOLL = 1 << 1;    // 2
    public static final int IORING_SETUP_SQ_AFF = 1 << 2;    // 4
    public static final int IORING_SETUP_CQSIZE = 1 << 3;    // 8
    public static final int IORING_SETUP_CLAMP = 1 << 4;     // 16
    public static final int IORING_SETUP_ATTACH_WQ = 1 << 5; // 32
    public static final int IORING_SETUP_R_DISABLED = 1 << 6; // 64

    /**
     * io_uring_register opcodes.
     */
    public static final int IORING_REGISTER_BUFFERS = 0;
    public static final int IORING_UNREGISTER_BUFFERS = 1;
    
    /**
     * SQ ring flags.
     */
    public static final int IORING_SQ_NEED_WAKEUP = 1 << 0;

    /**
     * io_uring_enter flags.
     */
    public static final int IORING_ENTER_GETEVENTS = 1 << 0;
    public static final int IORING_ENTER_SQ_WAKEUP = 1 << 1;
    public static final int IORING_ENTER_SQ_WAIT = 1 << 2;
    public static final int IORING_ENTER_EXT_ARG = 1 << 3;
    public static final int IORING_ENTER_REGISTERED_RING = 1 << 4;

    /**
     * Send/Recv flags.
     */
    public static final int IORING_RECVSEND_FIXED_BUF = 1 << 0;
    
    /**
     * {@code mmap} 的内存保护和映射常量。
     */
    // mmap constants
    public static final int PROT_READ = 0x1;
    public static final int PROT_WRITE = 0x2;
    public static final int MAP_SHARED = 0x01;
    public static final int MAP_POPULATE = 0x08000;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final long MAP_FAILED = -1L;

    /**
     * 套接字族和协议常量。
     */
    // Socket constants
    public static final int AF_UNIX = 1;
    public static final int AF_INET = 2;
    public static final int SOCK_STREAM = 1;
    public static final int IPPROTO_TCP = 6;
    
    // fcntl
    public static final int F_GETFL = 3;
    public static final int F_SETFL = 4;
    public static final int O_NONBLOCK = 04000;
    
    /**
     * IPv4 地址的 {@code sockaddr_in} 结构布局。
     */
    public static final StructLayout SOCKADDR_IN_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("sin_family"),
        ValueLayout.JAVA_SHORT.withName("sin_port"), // Big Endian
        ValueLayout.JAVA_INT.withName("sin_addr"),   // Big Endian
        MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("sin_zero")
    );
}
