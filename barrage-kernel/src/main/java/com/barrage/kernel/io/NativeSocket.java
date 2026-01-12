package com.barrage.kernel.io;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Linux BSD Socket API 的原生 Java 封装。
 * <p>
 * 该类利用 Java 22+ 的 {@link Linker} 和 {@link SymbolLookup} 机制，
 * 绕过 JVM 的 {@code java.nio.channels} 层，直接调用 `libc.so.6` 中的系统函数。
 *
 * <h2>核心功能：</h2>
 * <ul>
 * <li><b>直通内核：</b> 直接执行 {@code socket}, {@code bind}, {@code listen}, {@code connect} 等系统调用。</li>
 * <li><b>端口复用 (SO_REUSEADDR/PORT)：</b> 提供 {@link #setReuseAddr()} 方法，显式开启内核级负载均衡和快速重启能力。</li>
 * <li><b>异常透传：</b> 通过 {@link Linker.Option#captureCallState} 捕获原生 {@code errno}，将系统错误码转换为 Java IOException。</li>
 * </ul>
 *
 * <h2>性能优势：</h2>
 * 相比 JDK NIO Channel，该类减少了 JVM 内部的状态维护和对象分配开销，
 * 专为配合 {@code io_uring} 使用文件描述符 (FD) 场景设计。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public final class NativeSocket implements AutoCloseable {
    private final int fd;

    /**
     * 创建一个新的 TCP Socket (IPv4)。
     * <p>
     * 对应系统调用：{@code socket(AF_INET, SOCK_STREAM, 0)}
     *
     * @throws IOException 如果系统调用失败
     */
    public NativeSocket() throws IOException {
        this.fd = NativeLib.socketOrThrow(2, 1, 0); // AF_INET, SOCK_STREAM
    }

    /**
     * 包装现有的文件描述符。
     *
     * @param fd 原始文件描述符 (Raw File Descriptor)
     */
    public NativeSocket(int fd) { this.fd = fd; }

    /**
     * 获取原始文件描述符。
     * @return int 类型的 FD
     */
    public int getFd() { return fd; }

    /**
     * 开启端口复用选项 (核心优化)。
     * <p>
     * 在 {@code bind} 之前必须调用此方法。设置了两个关键 Socket 选项：
     * <ol>
     * <li><b>SO_REUSEADDR (level 1, name 2):</b>
     * 允许服务器在 socket 处于 {@code TIME_WAIT} 状态时（如刚重启）立即重新绑定端口。
     * 解决了 "Address already in use: bind" 错误。</li>
     *
     * <li><b>SO_REUSEPORT (level 1, name 15):</b>
     * 允许将多个 Socket 绑定到同一个 IP:PORT。这对于 "Thread-Per-Core" 模型至关重要，
     * 它让 Linux 内核自动在多个 Worker 线程间对入站连接进行负载均衡 (Sharding)，
     * 减少了用户态的锁竞争。</li>
     * </ol>
     *
     * @throws IOException 如果 setsockopt 调用失败
     */
    public void setReuseAddr() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opt = arena.allocate(ValueLayout.JAVA_INT, 1);
            opt.set(ValueLayout.JAVA_INT, 0, 1); // 设置为 1 表示开启

            // 1. 设置 SO_REUSEADDR (Level 1=SOL_SOCKET, Name 2=SO_REUSEADDR)
            NativeLib.setsockoptOrThrow(fd, 1, 2, opt, 4);

            // 2. 设置 SO_REUSEPORT (Level 1=SOL_SOCKET, Name 15=SO_REUSEPORT)
            // 注意：仅 Linux 3.9+ 支持
            NativeLib.setsockoptOrThrow(fd, 1, 15, opt, 4);
        }
    }

    /**
     * 绑定 Socket 到指定端口。
     * <p>
     * 对应系统调用：{@code bind(fd, sockaddr*, len)}
     *
     * @param port 端口号 (0-65535)
     * @throws IOException 如果绑定失败 (如端口被占用且未开启 Reuse)
     */
    public void bind(int port) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            // 构造 sockaddr_in 结构体
            MemorySegment addr = arena.allocate(NativeConstants.SOCKADDR_IN_LAYOUT);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // sin_family = AF_INET
            addr.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port)); // sin_port (网络字节序)
            addr.set(ValueLayout.JAVA_INT, 4, 0); // sin_addr = INADDR_ANY (0.0.0.0)

            NativeLib.bindOrThrow(fd, addr, (int) addr.byteSize());
        }
    }

    /**
     * 开始监听连接。
     * <p>
     * 对应系统调用：{@code listen(fd, backlog)}
     *
     * @param backlog 全连接队列的最大长度。在高并发场景下，建议设置较大的值（如 4096）以防 SYN Flood 或丢包。
     * @throws IOException 如果监听失败
     */
    public void listen(int backlog) throws IOException {
        NativeLib.listenOrThrow(fd, backlog);
    }

    /**
     * 连接到指定服务器 (客户端模式)。
     * <p>
     * 对应系统调用：{@code connect(fd, sockaddr*, len)}
     *
     * @param ip   目标 IPv4 地址
     * @param port 目标端口
     * @return {@code true} 如果连接成功, {@code false} 失败
     */
    public boolean connect(String ip, int port) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sa = arena.allocate(16); // sockaddr_in size
            sa.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // AF_INET
            sa.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port));
            sa.set(ValueLayout.JAVA_INT, 4, NativeLib.inet_addr(arena.allocateFrom(ip)));
            return NativeLib.connect(fd, sa, 16) == 0;
        } catch (Throwable t) { return false; }
    }

    /**
     * 关闭文件描述符。
     * <p>
     * 对应系统调用：{@code close(fd)}
     */
    @Override
    public void close() { NativeLib.close(fd); }

    /**
     * 内部静态类，持有 Native 函数的 MethodHandle。
     * <p>
     * 这里集中管理了与 libc 的动态链接和符号查找。
     */
    private static class NativeLib {
        static final Linker L = Linker.nativeLinker();
        static final SymbolLookup SL = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

        // --- MethodHandle 定义 ---
        // 使用 downcallHandle 创建从 Java 到 Native 的调用存根

        static final MethodHandle SOCKET = L.downcallHandle(
                SL.find("socket").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // 使用 captureCallState("errno") 捕获原生错误码
        static final MethodHandle BIND = L.downcallHandle(
                SL.find("bind").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno")
        );

        static final MethodHandle SETSOCKOPT = L.downcallHandle(
                SL.find("setsockopt").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno")
        );

        static final MethodHandle LISTEN = L.downcallHandle(
                SL.find("listen").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno")
        );

        static final MethodHandle HTONS = L.downcallHandle(
                SL.find("htons").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT)
        );

        static final MethodHandle INET_ADDR = L.downcallHandle(
                SL.find("inet_addr").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        static final MethodHandle CONNECT = L.downcallHandle(
                SL.find("connect").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.captureCallState("errno")
        );

        static final MethodHandle CLOSE = L.downcallHandle(
                SL.find("close").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // --- 错误捕获结构布局 ---
        static final StructLayout CAPTURE = MemoryLayout.structLayout(ValueLayout.JAVA_INT.withName("errno"));
        static final VarHandle VH_ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        // --- 包装方法 ---

        static int socketOrThrow(int d, int t, int p) throws IOException {
            try {
                int fd = (int)SOCKET.invokeExact(d,t,p);
                if(fd < 0) throw new IOException("socket fail");
                return fd;
            } catch (Throwable e) { throw new IOException(e); }
        }

        static void bindOrThrow(int f, MemorySegment a, int l) throws IOException {
            try(Arena z = Arena.ofConfined()){
                MemorySegment e = z.allocate(CAPTURE);
                if((int)BIND.invokeExact(e, f, a, l) < 0)
                    throw new IOException("bind fail: " + VH_ERRNO.get(e, 0L));
            } catch(Throwable t){
                if(t instanceof IOException) throw (IOException)t;
                throw new IOException(t);
            }
        }

        static void setsockoptOrThrow(int f, int l, int n, MemorySegment v, int len) throws IOException {
            try(Arena z = Arena.ofConfined()){
                MemorySegment e = z.allocate(CAPTURE);
                if((int)SETSOCKOPT.invokeExact(e, f, l, n, v, len) < 0)
                    throw new IOException("setsockopt fail: " + VH_ERRNO.get(e, 0L));
            } catch(Throwable t){
                if(t instanceof IOException) throw (IOException)t;
                throw new IOException(t);
            }
        }

        static void listenOrThrow(int f, int b) throws IOException {
            try(Arena z = Arena.ofConfined()){
                MemorySegment e = z.allocate(CAPTURE);
                if((int)LISTEN.invokeExact(e,f,b) < 0)
                    throw new IOException("listen fail: " + VH_ERRNO.get(e,0L));
            } catch (Throwable t){ throw new IOException(t); }
        }

        static short htons(short p) {
            try { return(short)HTONS.invokeExact(p); } catch(Throwable e){ return 0; }
        }

        static int inet_addr(MemorySegment ip) {
            try { return (int)INET_ADDR.invokeExact(ip); } catch(Throwable e){ return 0; }
        }

        static int connect(int f, MemorySegment addr, int len) {
            try(Arena a = Arena.ofConfined()){
                MemorySegment e = a.allocate(CAPTURE);
                return (int)CONNECT.invokeExact(e, f, addr, len);
            } catch(Throwable t){ return -1; }
        }

        static void close(int f) {
            try { CLOSE.invokeExact(f); } catch(Throwable e){}
        }
    }
}