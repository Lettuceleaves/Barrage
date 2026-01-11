package com.barrage.kernel.io;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.io.Closeable;
import java.io.IOException;

import static com.barrage.kernel.io.NativeConstants.*;

/**
 * Linux {@code io_uring} 异步 I/O 接口的底层 Java 封装。
 * <p>
 * 该类是 Barrage Kernel 的核心组件，利用 Java 22+ 的 FFI (Foreign Function &amp; Memory API)
 * 直接与操作系统内核交互，绕过了传统的 JDK NIO 层。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>纯堆外内存操作：</b> 使用 {@link MemorySegment} 和 {@link Arena} 管理内存，实现 Zero-GC。</li>
 * <li><b>共享环形缓冲区：</b> 通过 {@code mmap} 将内核的提交队列 (SQ) 和完成队列 (CQ) 映射到用户空间，
 * 允许在不陷入内核的情况下直接读写队列指针。</li>
 * <li><b>系统调用批处理：</b> 支持通过单次 {@code io_uring_enter} 调用同时完成“提交任务”和“等待结果”，
 * 极大减少上下文切换开销。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b> 该类设计用于单线程事件循环（Thread-Per-Core 模型）。
 * 多线程并发访问同一个实例会导致环形缓冲区指针错乱。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 * @see <a href="https://kernel.dk/io_uring.pdf">io_uring 设计论文</a>
 */
public final class IoUring implements Closeable {

    /**
     * 完成队列条目 (Completion Queue Entry) 的轻量级载体。
     * <p>
     * 用于从 {@link #peekCqe(Cqe)} 中获取结果，避免创建新对象。
     */
    public static class Cqe {
        /** 操作结果，>=0 表示成功字节数，<0 表示错误码 (errno 的负值) */
        @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Consumed by external user code")
        public int res;
        /** 用户数据，通常用于存储回调上下文索引或指针 */
        @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Consumed by external user code")
        public long userData;
    }

    // --- Native Linker & Handles ---
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBURING = SymbolLookup.libraryLookup("liburing.so.2", Arena.global());
    private static final SymbolLookup LIBC = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

    private static final MethodHandle IO_URING_SETUP;
    private static final MethodHandle IO_URING_ENTER;
    private static final MethodHandle MMAP;
    private static final MethodHandle MUNMAP;
    private static final MethodHandle CLOSE;

    static {
        try {
            // 初始化 native 方法句柄，绑定到 libc 和 liburing 的导出符号
            IO_URING_SETUP = LINKER.downcallHandle(LIBURING.find("io_uring_setup").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            IO_URING_ENTER = LINKER.downcallHandle(LIBURING.find("io_uring_enter").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MMAP = LINKER.downcallHandle(LIBC.find("mmap").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            MUNMAP = LINKER.downcallHandle(LIBC.find("munmap").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            CLOSE = LINKER.downcallHandle(LIBC.find("close").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        } catch (Throwable e) { throw new ExceptionInInitializerError(e); }
    }

    private final int ringFd;
    private final Arena arena;

    // --- Submission Queue (SQ) 指针 ---
    private final MemorySegment sqRing;
    private final MemorySegment sqes;
    private final MemorySegment sqKHead;
    private final MemorySegment sqKTail;
    private final MemorySegment sqKRingMask;
    private final MemorySegment sqKRay;
    private final int sqRingEntries;

    // --- Completion Queue (CQ) 指针 ---
    private final MemorySegment cqRing;
    private final MemorySegment cqKHead;
    private final MemorySegment cqKTail;
    private final MemorySegment cqKRingMask;
    private final MemorySegment cqes;
    private final int cqRingEntries;

    // 为了避免重复创建 Slice 对象，预先缓存每个 Slot 的 Segment
    private final MemorySegment[] sqeSegments;
    private final MemorySegment[] cqeSegments;

    private static final VarHandle VH_INT = ValueLayout.JAVA_INT.varHandle();

    /** 提交队列的本地缓存尾指针，避免频繁读取内核映射内存 */
    private int sqCachedTail;
    /** 上次提交到内核的尾指针位置 */
    private int sqLastSubmitTail;

    /**
     * 初始化 io_uring 实例。
     * <p>
     * 该构造函数执行以下关键步骤：
     * <ol>
     * <li>调用 {@code io_uring_setup} 系统调用初始化内核结构。</li>
     * <li>计算 SQ 和 CQ 的内存偏移量。</li>
     * <li>使用 {@code mmap} 将内核环形缓冲区映射到当前进程的虚拟地址空间。</li>
     * <li>初始化用于访问 Head/Tail 指针的 {@link MemorySegment}。</li>
     * </ol>
     *
     * @param entries 队列深度（必须是 2 的幂），如 1024, 2048, 4096。
     * @throws IOException 如果 setup 失败或 mmap 失败。
     */
    public IoUring(int entries) throws IOException {
        this.arena = Arena.ofShared();
        MemorySegment params = arena.allocate(IO_URING_PARAMS_LAYOUT);

        int ret;
        try { ret = (int) IO_URING_SETUP.invokeExact(entries, params); } catch (Throwable e) { throw new IOException(e); }
        if (ret < 0) throw new IOException("setup failed: " + ret);
        this.ringFd = ret;

        // Map SQ (Submission Queue)
        long sqRingSize = (long) params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("array"))) + ((long) entries * 4);
        this.sqRing = mmap(sqRingSize, IORING_OFF_SQ_RING);

        // Map SQES (Submission Queue Entries - 实际存放请求结构体的区域)
        this.sqRingEntries = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_entries")));
        this.sqes = mmap((long)sqRingEntries * SQE_LAYOUT.byteSize(), IORING_OFF_SQES);

        // Map CQ (Completion Queue)
        this.cqRingEntries = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_entries")));
        long cqRingSize = (long) params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("cqes"))) + ((long)cqRingEntries * CQE_LAYOUT.byteSize());
        this.cqRing = mmap(cqRingSize, IORING_OFF_CQ_RING);

        // Init Pointers (初始化对内核共享内存中 Head/Tail 等字段的引用)
        this.sqKHead = sqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("head"))), 4);
        this.sqKTail = sqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("tail"))), 4);
        this.sqKRingMask = sqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("ring_mask"))), 4);
        this.sqKRay = sqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("sq_off"), MemoryLayout.PathElement.groupElement("array"))), (long)sqRingEntries * 4);

        this.cqKHead = cqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("head"))), 4);
        this.cqKTail = cqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("tail"))), 4);
        this.cqKRingMask = cqRing.asSlice(params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("ring_mask"))), 4);
        long cqesOff = params.get(ValueLayout.JAVA_INT, IO_URING_PARAMS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq_off"), MemoryLayout.PathElement.groupElement("cqes")));
        this.cqes = cqRing.asSlice(cqesOff, (long)cqRingEntries * CQE_LAYOUT.byteSize());

        // 预先切片，避免运行时开销
        this.sqeSegments = new MemorySegment[sqRingEntries];
        for(int i=0; i<sqRingEntries; i++) sqeSegments[i] = sqes.asSlice((long)i * SQE_LAYOUT.byteSize(), SQE_LAYOUT.byteSize());

        this.cqeSegments = new MemorySegment[cqRingEntries];
        for(int i=0; i<cqRingEntries; i++) cqeSegments[i] = cqes.asSlice((long)i * CQE_LAYOUT.byteSize(), CQE_LAYOUT.byteSize());

        // 初始化 SQ Array 映射 (Indirection array)
        for(int i=0; i<sqRingEntries; i++) sqKRay.setAtIndex(ValueLayout.JAVA_INT, i, i);

        // 同步尾指针
        sqCachedTail = (int)VH_INT.getAcquire(sqKTail, 0L);
        sqLastSubmitTail = sqCachedTail;
    }

    /**
     * 辅助方法：执行 native mmap 系统调用。
     */
    private MemorySegment mmap(long size, long offset) throws IOException {
        try {
            MemorySegment addr = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, ringFd, offset);
            if (addr.equals(MemorySegment.ofAddress(MAP_FAILED))) throw new IOException("mmap failed");
            // 将原始内存地址重新解释为由 arena 管理的 Segment
            return addr.reinterpret(size, arena, null);
        } catch (Throwable e) { throw new IOException(e); }
    }

    /**
     * 获取下一个可用的提交队列条目 (SQE)。
     * <p>
     * 该方法不会执行系统调用。它仅仅是在用户态更新本地缓存的 Tail 指针，并返回对应的内存块供用户填充。
     * 调用此方法后，用户必须调用 {@link #prepSend} / {@link #prepRead} 等方法填充内容，
     * 最后调用 {@link #submitAndWait} 告知内核。
     *
     * @return 可用的 SQE 内存段；如果队列已满，则返回 null。
     */
    public MemorySegment nextSqe() {
        int head = (int) VH_INT.getAcquire(sqKHead, 0L);
        int tail = sqCachedTail;

        // 检查队列是否已满 (Ring Buffer Full)
        if ((tail - head) >= sqRingEntries) return null;

        // 获取 SQE 插槽并清零
        MemorySegment sqe = sqeSegments[tail & (int)VH_INT.getAcquire(sqKRingMask, 0L)];
        sqe.fill((byte) 0);

        sqCachedTail++;
        return sqe;
    }

    /**
     * 提交所有待处理的 SQE，并等待完成事件。
     * <p>
     * 这是驱动 io_uring 运转的核心方法。它做了两件事：
     * <ol>
     * <li>更新内核可见的 Tail 指针，告知内核有新任务（Flush SQ）。</li>
     * <li>调用 {@code io_uring_enter} 进入内核，提交任务并根据 {@code minComplete} 决定是否阻塞等待。</li>
     * </ol>
     *
     * @param minComplete 最少需要等待完成的事件数量。
     * <ul>
     * <li>如果为 0：仅提交任务，不等待，立即返回（非阻塞模式）。</li>
     * <li>如果 > 0：提交任务并阻塞当前线程，直到至少有 n 个事件完成（阻塞/混合模式）。</li>
     * </ul>
     * @return 系统调用返回值，通常表示消耗/提交的事件数。
     * @throws IOException 如果系统调用失败。
     */
    public int submitAndWait(int minComplete) throws IOException {
        int tail = sqCachedTail;
        int toSubmit = tail - sqLastSubmitTail;

        // 只有当有新任务需要提交，或者我们显式要求等待(minComplete > 0)时才调用 syscall
        // 更新内核可见的 Tail 指针 (StoreRelease 保证顺序)
        if (toSubmit > 0) {
            VH_INT.setRelease(sqKTail, 0L, tail);
        }

        int ret;
        try {
            // 如果 minComplete > 0，必须设置 IORING_ENTER_GETEVENTS 标志
            int flags = (minComplete > 0) ? IORING_ENTER_GETEVENTS : 0;
            ret = (int) IO_URING_ENTER.invokeExact(ringFd, toSubmit, minComplete, flags, MemorySegment.NULL);
        } catch (Throwable e) { throw new IOException(e); }

        if (toSubmit > 0) sqLastSubmitTail = tail;
        return ret;
    }

    /**
     * [新增] 提交待处理任务到内核，但不等待。
     * <p>
     * 等价于调用 {@code submitAndWait(0)}。
     * 当提交队列满时，调用此方法将任务刷入内核，从而腾出 SQE 槽位。
     *
     * @return 提交的任务数量
     * @throws IOException 如果系统调用失败
     */
    public int submit() throws IOException {
        return submitAndWait(0);
    }

    /**
     * 提交待处理任务，但不等待任何结果。
     * 等价于 {@code submitAndWait(0)}。
     *
     * @return 系统调用返回值
     * @throws IOException 如果调用失败
     */
    public int submitAndGet() throws IOException {
        return submitAndWait(0);
    }

    /**
     * 非阻塞地检查完成队列 (CQ)。
     * <p>
     * 这是一个纯内存操作，不涉及任何系统调用。它直接读取共享内存中的 Head/Tail 指针。
     *
     * @param out 用于承载结果的容器对象。如果发现事件，结果将填充到此对象中。
     * @return {@code true} 如果成功获取到一个事件；{@code false} 如果队列为空。
     */
    public boolean peekCqe(Cqe out) {
        // 使用 Acquire 语义读取 Head/Tail，确保看到内核最新的更新
        int head = (int) VH_INT.getAcquire(cqKHead, 0L);
        int tail = (int) VH_INT.getAcquire(cqKTail, 0L);

        if (head == tail) return false; // 队列为空

        int mask = (int) VH_INT.getAcquire(cqKRingMask, 0L);
        MemorySegment cqe = cqeSegments[head & mask];

        // 解析 CQE 结构体
        out.res = cqe.get(ValueLayout.JAVA_INT, CQE_OFF_RES);
        out.userData = cqe.get(ValueLayout.JAVA_LONG, CQE_OFF_USER_DATA);

        // 更新 Head 指针，表示该事件已被消费 (Release 语义)
        VH_INT.setRelease(cqKHead, 0L, head + 1);
        return true;
    }

    // --- SQE 填充辅助方法 (Opcode Prep Helpers) ---

    /**
     * 准备一个 NOP (No Operation) 请求。通常用于测试或占位。
     */
    public void prepNop(MemorySegment sqe) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_NOP);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, -1);
    }

    /**
     * 准备一个 SEND 请求 (相当于 send 系统调用)。
     *
     * @param sqe   由 {@link #nextSqe()} 获取的内存段
     * @param fd    目标文件描述符
     * @param buf   数据缓冲区 (Off-heap MemorySegment)
     * @param len   数据长度
     * @param flags 发送标志 (如 MSG_DONTWAIT)
     */
    public void prepSend(MemorySegment sqe, int fd, MemorySegment buf, int len, int flags) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_SEND);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, fd);
        sqe.set(ValueLayout.JAVA_LONG, SQE_OFF_ADDR, buf.address());
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_LEN, len);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_RW_FLAGS, flags);
    }

    /**
     * 准备一个 READ 请求 (相当于 read 系统调用)。
     *
     * @param sqe   由 {@link #nextSqe()} 获取的内存段
     * @param fd    源文件描述符
     * @param buf   接收缓冲区 (Off-heap MemorySegment)
     * @param len   读取长度
     * @param flags 读取标志
     */
    public void prepRead(MemorySegment sqe, int fd, MemorySegment buf, int len, int flags) {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OFF_OPCODE, IORING_OP_READ);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_FD, fd);
        sqe.set(ValueLayout.JAVA_LONG, SQE_OFF_ADDR, buf.address());
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_LEN, len);
        sqe.set(ValueLayout.JAVA_INT, SQE_OFF_RW_FLAGS, flags);
    }

    /**
     * 关闭 io_uring 实例。
     * <p>
     * 释放所有堆外内存（Arena），并关闭内核中的 Ring 文件描述符。
     */
    @Override
    public void close() throws IOException {
        try { CLOSE.invokeExact(ringFd); } catch(Throwable e) {}
        // 关闭 Arena 会自动 unmap 内存
        arena.close();
    }
}