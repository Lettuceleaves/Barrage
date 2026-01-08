package com.barrage.kernel.io;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.io.Closeable;
import java.io.IOException;

import static com.barrage.kernel.io.NativeConstants.*;

/**
 * 基于 Java 25 FFM API 的 io_uring 封装。
 * 使用原始系统调用以避免对 liburing 结构布局的依赖。
 */
public class IoUring implements Closeable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
    private static final SymbolLookup LIBURING = SymbolLookup.libraryLookup("liburing.so.2", Arena.global());

    // io_uring_setup(int entries, struct io_uring_params *p)
    private static final MethodHandle IO_URING_SETUP;
    // io_uring_enter(int fd, unsigned to_submit, unsigned min_complete, unsigned flags, sigset_t *sig)
    private static final MethodHandle IO_URING_ENTER;
    // io_uring_register(int fd, unsigned opcode, void *arg, unsigned nr_args)
    private static final MethodHandle IO_URING_REGISTER;
    
    // Mmap handle: void* mmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset)
    private static final MethodHandle MMAP;
    // Munmap handle: int munmap(void* addr, size_t length)
    private static final MethodHandle MUNMAP;
    // Close handle: int close(int fd)
    private static final MethodHandle CLOSE;
    // Fcntl handle: int fcntl(int fd, int cmd, int arg)
    private static final MethodHandle FCNTL;
    // Socketpair handle: int socketpair(int domain, int type, int protocol, void* sv)
    private static final MethodHandle SOCKETPAIR;
    
    // Socket functions
    private static final MethodHandle SOCKET;
    private static final MethodHandle CONNECT;
    private static final MethodHandle HTONS;
    private static final MethodHandle INET_ADDR;

    static {
        try {
            IO_URING_SETUP = LINKER.downcallHandle(
                LIBURING.find("io_uring_setup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            
            IO_URING_ENTER = LINKER.downcallHandle(
                LIBURING.find("io_uring_enter").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );

            IO_URING_REGISTER = LINKER.downcallHandle(
                LIBURING.find("io_uring_register").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );

            MMAP = LINKER.downcallHandle(
                LIBC.find("mmap").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
            );

            MUNMAP = LINKER.downcallHandle(
                LIBC.find("munmap").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            CLOSE = LINKER.downcallHandle(
                LIBC.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            SOCKET = LINKER.downcallHandle(
                LIBC.find("socket").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            CONNECT = LINKER.downcallHandle(
                LIBC.find("connect").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );

            FCNTL = LINKER.downcallHandle(
                LIBC.find("fcntl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );

            SOCKETPAIR = LINKER.downcallHandle(
                LIBC.find("socketpair").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
            
            HTONS = LINKER.downcallHandle(
                LIBC.find("htons").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT)
            );
            
            INET_ADDR = LINKER.downcallHandle(
                LIBC.find("inet_addr").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            );
        } catch (Throwable e) {
            System.err.println("CRITICAL: Failed to initialize IoUring native handles.");
            e.printStackTrace();
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * io_uring 实例的文件描述符。
     */
    private final int ringFd;

    /**
     * 用于管理所有 Ring 相关内存的 Arena。
     */
    private final Arena arena;
    
    // SQ 环相关内存段
    /**
     * SQ 环的完整内存段（包含 head, tail, mask 等）。
     */
    private final MemorySegment sqRing;
    /**
     * 提交队列条目 (SQE) 的基地址内存段。
     */
    private final MemorySegment sqes;
    /**
     * 内核中的 SQ head 指针。
     */
    private final MemorySegment sqKHead;
    /**
     * 内核中的 SQ tail 指针。
     */
    private final MemorySegment sqKTail;
    /**
     * SQ 环的掩码（Mask）。
     */
    private final MemorySegment sqKRingMask;
    /**
     * SQ 环的索引数组（Array）。
     */
    private final MemorySegment sqKRay;
    /**
     * SQ 环的状态标志（Flags）。
     */
    private final MemorySegment sqKFlags;
    /**
     * SQ 环的总容量。
     */
    private final int sqRingEntries;

    // CQ 环相关内存段
    /**
     * CQ 环的完整内存段。
     */
    private final MemorySegment cqRing;
    /**
     * 内核中的 CQ head 指针。
     */
    private final MemorySegment cqKHead;
    /**
     * 内核中的 CQ tail 指针。
     */
    private final MemorySegment cqKTail;
    /**
     * CQ 环的掩码。
     */
    private final MemorySegment cqKRingMask;
    /**
     * CQ 环的溢出计数器。
     */
    private final MemorySegment cqKOverflow;
    /**
     * 完成队列条目 (CQE) 的基地址内存段。
     */
    private final MemorySegment cqes;
    /**
     * CQ 环的总容量（通常是 SQ 的 2 倍）。
     */
    private final int cqRingEntries;
    
    /**
     * io_uring 实例的标志位。
     */
    private final int flags;

    /**
     * 内核支持的特性。
     */
    private final int features;
    
    /**
     * 预切分的 SQE 段，避免在热路径中使用 {@code asSlice()} 导致堆分配。
     */
    private final MemorySegment[] sqeSegments;

    /**
     * 预切分的 CQE 段，避免在热路径中使用 {@code asSlice()} 导致堆分配。
     */
    private final MemorySegment[] cqeSegments;

    /**
     * 创建一个新的 io_uring 实例。
     *
     * @param entries 提交队列中的条目数。
     * @throws IOException 如果初始化或内存映射失败。
     */
    public IoUring(int entries) throws IOException {
        this(entries, 0);
    }

    /**
     * 创建具有指定标志的 io_uring 实例。
     *
     * @param entries 提交队列中的条目数。
     * @param flags   要传递给 {@code io_uring_setup} 的标志（如 IORING_SETUP_SQPOLL）。
     * @throws IOException 如果初始化或内存映射失败。
     */
    public IoUring(int entries, int flags) throws IOException {
        this.arena = Arena.ofShared();
        
        // 1. 调用 io_uring_setup
        MemorySegment params = arena.allocate(IO_URING_PARAMS_LAYOUT);
        params.set(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags")), flags);
        
        int ret;
        try {
            ret = (int) IO_URING_SETUP.invokeExact(entries, params);
        } catch (Throwable e) {
            throw new IOException("Failed to invoke io_uring_setup", e);
        }

        if (ret < 0) {
            throw new IOException("io_uring_setup failed with error: " + ret);
        }
        this.ringFd = ret;
        this.flags = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags")));
        this.features = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("features")));

        // 2. Mmap SQ_RING
        long sqRingSize = (long) params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("array"))) 
                          + ((long) entries * ValueLayout.JAVA_INT.byteSize()); // array size
        
        try {
            MemorySegment sqRingAddr = (MemorySegment) MMAP.invokeExact(
                MemorySegment.NULL, 
                sqRingSize, 
                PROT_READ | PROT_WRITE, 
                MAP_SHARED | MAP_POPULATE, 
                ringFd, 
                IORING_OFF_SQ_RING
            );
            if (sqRingAddr.equals(MemorySegment.ofAddress(MAP_FAILED))) {
                 throw new IOException("mmap SQ ring failed");
            }
            this.sqRing = sqRingAddr.reinterpret(sqRingSize, arena, null);
        } catch (Throwable e) {
            throw new IOException("mmap SQ ring failed", e);
        }

        // 3. Mmap SQES
        this.sqRingEntries = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_entries")));
        long sqesSize = (long) sqRingEntries * SQE_LAYOUT.byteSize();
        try {
            MemorySegment sqesAddr = (MemorySegment) MMAP.invokeExact(
                MemorySegment.NULL,
                sqesSize,
                PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_POPULATE,
                ringFd,
                IORING_OFF_SQES
            );
            if (sqesAddr.equals(MemorySegment.ofAddress(MAP_FAILED))) {
                throw new IOException("mmap SQES failed");
            }
            this.sqes = sqesAddr.reinterpret(sqesSize, arena, null);
        } catch (Throwable e) {
            throw new IOException("mmap SQES failed", e);
        }

        // 4. Mmap CQ_RING
        long cqRingSize = (long) params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("cqes")))
            + ((long) params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_entries"))) * CQE_LAYOUT.byteSize());

        try {
            MemorySegment cqRingAddr = (MemorySegment) MMAP.invokeExact(
                MemorySegment.NULL,
                cqRingSize,
                PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_POPULATE,
                ringFd,
                IORING_OFF_CQ_RING
            );
            if (cqRingAddr.equals(MemorySegment.ofAddress(MAP_FAILED))) {
                throw new IOException("mmap CQ ring failed");
            }
            this.cqRing = cqRingAddr.reinterpret(cqRingSize, arena, null);
        } catch (Throwable e) {
            throw new IOException("mmap CQ ring failed", e);
        }
        
        // 5. 初始化偏移量
        // SQ
        long sqHeadOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("head")));
        long sqTailOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("tail")));
        long sqMaskOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("ring_mask")));
        long sqArrayOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("array")));
        long sqFlagsOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("flags")));
        
        this.sqKHead = sqRing.asSlice(sqHeadOff, 4);
        this.sqKTail = sqRing.asSlice(sqTailOff, 4);
        this.sqKRingMask = sqRing.asSlice(sqMaskOff, 4);
        this.sqKRay = sqRing.asSlice(sqArrayOff, (long) sqRingEntries * 4);
        this.sqKFlags = sqRing.asSlice(sqFlagsOff, 4);

        // CQ
        this.cqRingEntries = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_entries")));
        long cqHeadOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("head")));
        long cqTailOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("tail")));
        long cqMaskOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("ring_mask")));
        long cqOverflowOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("overflow")));
        long cqesOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("cqes")));

        this.cqKHead = cqRing.asSlice(cqHeadOff, 4);
        this.cqKTail = cqRing.asSlice(cqTailOff, 4);
        this.cqKRingMask = cqRing.asSlice(cqMaskOff, 4);
        this.cqKOverflow = cqRing.asSlice(cqOverflowOff, 4);
        this.cqes = cqRing.asSlice(cqesOff, (long) cqRingEntries * CQE_LAYOUT.byteSize());

        // 6. 预切分段以避免在热循环中分配
        this.sqeSegments = new MemorySegment[sqRingEntries];
        for (int i = 0; i < sqRingEntries; i++) {
            sqeSegments[i] = this.sqes.asSlice((long) i * SQE_LAYOUT.byteSize(), SQE_LAYOUT.byteSize());
        }
        
        this.cqeSegments = new MemorySegment[cqRingEntries];
        for (int i = 0; i < cqRingEntries; i++) {
            cqeSegments[i] = this.cqes.asSlice((long) i * CQE_LAYOUT.byteSize(), CQE_LAYOUT.byteSize());
        }
        
        initLocalState();
    }

    private static final VarHandle VH_INT = ValueLayout.JAVA_INT.varHandle();

    private int sqCachedTail; 

    /**
     * 从内核内存初始化本地 SQ tail 追踪。
     * 应在构造函数之后或第一次使用前调用。
     */
    public final void initLocalState() {
        sqCachedTail = (int) VH_INT.getAcquire(sqKTail, 0L);
    }

    /**
     * 获取下一个可用的提交队列条目 (SQE)。
     * 此方法使用预切分段和本地 tail 追踪以确保零堆分配。
     *
     * @return 代表 SQE 的 {@link MemorySegment}，如果 Ring 已满则返回 {@code null}。
     */
    public MemorySegment nextSqe() {
        int head = (int) VH_INT.getAcquire(sqKHead, 0L); 
        int tail = sqCachedTail;
        int ringMask = (int) VH_INT.getAcquire(sqKRingMask, 0L);

        if ((tail - head) >= sqRingEntries) {
             return null;
        }
        
        int index = tail & ringMask;
        MemorySegment sqe = sqeSegments[index];
        sqe.fill((byte) 0);
        
        sqKRay.setAtIndex(ValueLayout.JAVA_INT, index, index);
        sqCachedTail++;
        return sqe;
    }
    
    /**
     * 准备一个 NOP (无操作) SQE。
     *
     * @param sqe 要填充的 SQE。
     */
    public void prepNop(MemorySegment sqe) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_NOP);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, -1);
    }
    
    /**
     * 准备一个 SEND 操作。
     *
     * @param sqe    要填充的 SQE。
     * @param fd     套接字文件描述符。
     * @param buf    包含要发送数据的缓冲区。
     * @param len    数据长度。
     * @param flags  操作标志。
     */
    public void prepSend(MemorySegment sqe, int fd, MemorySegment buf, int len, int flags) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_SEND);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, fd);
        sqe.set(ValueLayout.JAVA_LONG, SQE_OFF_ADDR, buf.address());
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_LEN, len);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_RW_FLAGS, flags);
    }

    /**
     * 准备一个 SEND_ZC (零拷贝发送) 操作。
     *
     * @param sqe      要填充的 SQE。
     * @param fd       套接字文件描述符。
     * @param buf      包含要发送数据的缓冲区。
     * @param len      数据长度。
     * @param flags    操作标志。
     * @param bufIndex 预注册缓冲区的索引（如果使用了 IORING_RECVSEND_FIXED_BUF）。
     */
    public void prepSendZc(MemorySegment sqe, int fd, MemorySegment buf, int len, int flags, int bufIndex) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_SEND_ZC);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, fd);
        sqe.set(ValueLayout.JAVA_LONG, SQE_OFF_ADDR, buf.address());
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_LEN, len);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_RW_FLAGS, flags);
        sqe.set(ValueLayout.JAVA_SHORT, SQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("buf_index")), (short) bufIndex);
    }

    /**
     * 准备一个 READ 操作。
     *
     * @param sqe    要填充的 SQE。
     * @param fd     套接字文件描述符。
     * @param buf    用于读取数据的缓冲区。
     * @param len    最大读取长度。
     * @param flags  操作标志。
     */
    public void prepRead(MemorySegment sqe, int fd, MemorySegment buf, int len, int flags) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_READ);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, fd);
        sqe.set(ValueLayout.JAVA_LONG, SQE_OFF_ADDR, buf.address());
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_LEN, len);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_RW_FLAGS, flags);
    }

    /**
     * 向内核提交待处理的 SQE。
     *
     * @return 提交的 SQE 数量，如果没有提交则返回 0。
     * @throws IOException 如果 {@code io_uring_enter} 系统调用失败。
     */
    public int submitAndGet() throws IOException {
        int tail = sqCachedTail;
        int currentKTail = (int) VH_INT.getAcquire(sqKTail, 0L);
        int submitted = tail - currentKTail;
        if (submitted > 0) {
            VH_INT.setRelease(sqKTail, 0L, tail); 
            
            // 优化：如果开启了 SQPOLL，且内核线程尚未进入休眠状态（NEED_WAKEUP 指标为 0），
            // 则无需执行任何系统调用。
            boolean needEnter = true;
            int enterFlags = 0;
            if ((flags & IORING_SETUP_SQPOLL) != 0) {
                int sqFlags = (int) VH_INT.getAcquire(sqKFlags, 0L);
                if ((sqFlags & IORING_SQ_NEED_WAKEUP) == 0) {
                    needEnter = false;
                } else {
                    enterFlags |= IORING_ENTER_SQ_WAKEUP;
                }
            }

            if (needEnter) {
                try {
                    int ret = (int) IO_URING_ENTER.invokeExact(ringFd, submitted, 0, enterFlags, MemorySegment.NULL);
                    if (ret < 0) {
                        throw new IOException("io_uring_enter failed: " + ret);
                    }
                    return ret;
                } catch (Throwable e) {
                    throw new IOException("io_uring_enter failed", e);
                }
            }
            return submitted;
        }
        return 0;
    }

    /**
     * 将一组缓冲区预注册给内核以加速 I/O 操作 (Zero-Copy)。
     *
     * @param segments 要注册的内存段。
     * @throws IOException 如果注册失败。
     */
    public void registerBuffers(MemorySegment... segments) throws IOException {
        int nr = segments.length;
        MemorySegment iovecs = arena.allocate(MemoryLayout.sequenceLayout(nr, MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("iov_base"),
            ValueLayout.JAVA_LONG.withName("iov_len")
        )));

        for (int i = 0; i < nr; i++) {
            long offset = i * (ValueLayout.ADDRESS.byteSize() + ValueLayout.JAVA_LONG.byteSize());
            iovecs.set(ValueLayout.ADDRESS, offset, segments[i]);
            iovecs.set(ValueLayout.JAVA_LONG, offset + ValueLayout.ADDRESS.byteSize(), segments[i].byteSize());
        }

        try {
            int ret = (int) IO_URING_REGISTER.invokeExact(ringFd, IORING_REGISTER_BUFFERS, iovecs, nr);
            if (ret < 0) {
                throw new IOException("io_uring_register(BUFFERS) failed: " + ret);
            }
        } catch (Throwable e) {
            throw new IOException("io_uring_register failed", e);
        }
    }
    
    /**
     * 等待至少一个完成队列条目 (CQE) 可用。
     * 此方法会阻塞直到发现完成条目。
     *
     * @return 来自 CQE 的结果代码（例如，发送/读取的字节数）。
     * @throws IOException 如果等待操作失败。
     */
    public int waitComplete() throws IOException {
        int head = (int) VH_INT.getAcquire(cqKHead, 0L);
        int tail = (int) VH_INT.getAcquire(cqKTail, 0L);
        int ringMask = (int) VH_INT.getAcquire(cqKRingMask, 0L); 
        
        while (head == tail) {
            try {
                // 等待至少 1 个完成条目
                int ret = (int) IO_URING_ENTER.invokeExact(ringFd, 0, 1, 0, MemorySegment.NULL);
                if (ret < 0) {
                    throw new IOException("io_uring_enter wait failed: " + ret);
                }
            } catch (Throwable e) {
                throw new IOException("io_uring_enter wait failed", e);
            }
            head = (int) VH_INT.getAcquire(cqKHead, 0L);
            tail = (int) VH_INT.getAcquire(cqKTail, 0L);
        }
        
        int index = head & ringMask;
        MemorySegment cqe = cqeSegments[index];
        int res = cqe.get(ValueLayout.JAVA_INT, CQE_OFF_RES);
        
        // 推进 head
        VH_INT.setRelease(cqKHead, 0L, head + 1);
        
        return res;
    }

    /**
     * 非阻塞地检查是否有完成条目。
     *
     * @return 如果有完成条目则返回结果，否则返回 {@code Integer.MIN_VALUE}。
     */
    public int peekComplete() {
        int head = (int) VH_INT.getAcquire(cqKHead, 0L);
        int tail = (int) VH_INT.getAcquire(cqKTail, 0L);
        int ringMask = (int) VH_INT.getAcquire(cqKRingMask, 0L);

        if (head == tail) {
            return Integer.MIN_VALUE;
        }

        int index = head & ringMask;
        MemorySegment cqe = cqeSegments[index];
        int res = cqe.get(ValueLayout.JAVA_INT, CQE_OFF_RES);

        // 推进 head
        VH_INT.setRelease(cqKHead, 0L, head + 1);

        return res;
    }

    /**
     * 关闭 io_uring 实例并释放所有关联内存。
     *
     * @throws IOException 如果关闭 Ring 文件描述符失败。
     */
    @Override
    public void close() throws IOException {
         try {
             CLOSE.invokeExact(ringFd);
         } catch(Throwable e) {
             // ignore
         }
         arena.close();
    }
    
    /**
     * 使用原始 libc 系统调用创建一个新的套接字。
     *
     * @param domain   协议族（例如 AF_INET）。
     * @param type     套接字类型（例如 SOCK_STREAM）。
     * @param protocol 要使用的协议。
     * @return 新套接字的文件描述符。
     * @throws IOException 如果套接字创建失败。
     */
    public int socket(int domain, int type, int protocol) throws IOException {
        try {
            int fd = (int) SOCKET.invokeExact(domain, type, protocol);
            if (fd < 0) {
                 throw new IOException("socket failed: " + fd);
            }
            return fd;
        } catch (Throwable e) {
             throw new IOException("socket invocation failed", e);
        }
    }
    
    /**
     * 将套接字连接到指定的远程地址和端口。
     *
     * @param sockfd 套接字文件描述符。
     * @param ip     远程 IP 地址字符串。
     * @param port   远程端口号。
     * @return 成功返回 0，失败返回负数错误代码。
     * @throws IOException 如果连接尝试失败。
     */
    public int connect(int sockfd, String ip, int port) throws IOException {
        MemorySegment addr = arena.allocate(SOCKADDR_IN_LAYOUT);
        
        short family = (short) AF_INET;
        short netPort;
        int netAddr;
        
        try {
             netPort = (short) HTONS.invokeExact((short) port);
             
             MemorySegment ipStr = arena.allocateFrom(ip);
             netAddr = (int) INET_ADDR.invokeExact(ipStr);
        } catch (Throwable e) {
             throw new IOException("htons/inet_addr failed", e);
        }
        
        addr.set(ValueLayout.JAVA_SHORT, SOCKADDR_IN_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sin_family")), family);
        addr.set(ValueLayout.JAVA_SHORT, SOCKADDR_IN_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sin_port")), netPort);
        addr.set(ValueLayout.JAVA_INT, SOCKADDR_IN_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sin_addr")), netAddr);
        
        try {
            int ret = (int) CONNECT.invokeExact(sockfd, addr, (int) SOCKADDR_IN_LAYOUT.byteSize());
            if (ret < 0) {
                 return ret; 
            }
            return 0;
        } catch (Throwable e) {
             throw new IOException("connect invocation failed", e);
        }
    }
    
    /**
     * 关闭文件描述符。
     *
     * @param fd 要关闭的文件描述符。
     */
    public void closeFd(int fd) {
        try {
            CLOSE.invokeExact(fd);
        } catch (Throwable e) {
            // ignore
        }
    }

    /**
     * 创建一对相互连接的无名套接字。
     *
     * @param domain   协议族。
     * @param type     套接字类型。
     * @param protocol 协议。
     * @param sv       用于存储两个文件描述符的内存段（需 8 字节）。
     * @return 成功返回 0，失败返回负数错误代码。
     */
    public int socketpair(int domain, int type, int protocol, MemorySegment sv) {
        try {
            return (int) SOCKETPAIR.invokeExact(domain, type, protocol, sv);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 设置文件描述符标志。
     *
     * @param fd    文件描述符。
     * @param cmd   命令（如 F_SETFL）。
     * @param flags 标志（如 O_NONBLOCK）。
     * @return 成功返回 0，失败返回 -1。
     */
    public int fcntl(int fd, int cmd, int flags) {
        try {
            return (int) FCNTL.invokeExact(fd, cmd, flags);
        } catch (Throwable e) {
            return -1;
        }
    }

}
