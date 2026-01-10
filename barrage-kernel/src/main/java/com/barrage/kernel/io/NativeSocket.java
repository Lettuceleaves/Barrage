package com.barrage.kernel.io;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * 基于 Java FFM (Foreign Function &amp; Memory) API 的原生 Socket 封装类。
 * <p>
 * 该类绕过了 Java NIO 的 {@link java.nio.channels.SocketChannel} 和 {@link java.net.Socket}，
 * 直接通过 {@link Linker} 调用宿主机 {@code libc.so.6} 中的标准 Socket 系统调用。
 * <p>
 * <b>设计目的：</b>
 * <ul>
 * <li><b>获取原始 FD：</b> io_uring 需要直接操作文件描述符 (File Descriptor)，而 JDK 标准库隐藏了这些细节。</li>
 * <li><b>减少开销：</b> 避免了 JDK Socket 实现中复杂的对象层级和同步锁，提供最纯粹的系统调用封装。</li>
 * <li><b>精确控制：</b> 允许直接操作 {@code setsockopt} 等底层选项，便于进行 TCP 调优。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 * @see <a href="https://man7.org/linux/man-pages/man7/socket.7.html">Linux Socket Man Page</a>
 */
public final class NativeSocket implements AutoCloseable {

    private final int fd;

    /**
     * 创建一个新的 TCP/IPv4 Socket。
     * <p>
     * 等价于调用 C 语言的 {@code socket(AF_INET, SOCK_STREAM, 0)}。
     *
     * @throws IOException 如果系统调用失败 (fd < 0)
     */
    public NativeSocket() throws IOException {
        this.fd = NativeLib.socketOrThrow(2, 1, 0); // AF_INET, SOCK_STREAM
    }

    /**
     * 包装一个现有的文件描述符。
     * <p>
     * 通常用于包装 {@code accept} 系统调用返回的客户端连接 FD。
     *
     * @param fd 有效的 Socket 文件描述符
     */
    public NativeSocket(int fd) {
        this.fd = fd;
    }

    /**
     * 获取当前 Socket 的原生文件描述符。
     *
     * @return int 类型的 fd
     */
    public int getFd() {
        return fd;
    }

    /**
     * 启用 {@code SO_REUSEADDR} 选项。
     * <p>
     * 允许服务器在重启后立即绑定到之前的端口，即使该端口仍处于 TIME_WAIT 状态。
     * 这对于高频重启的压测服务端至关重要。
     *
     * @throws IOException 如果 setsockopt 调用失败
     */
    public void setReuseAddr() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opt = arena.allocate(ValueLayout.JAVA_INT, 1);
            NativeLib.setsockoptOrThrow(fd, 1, 2, opt, 4); // SOL_SOCKET, SO_REUSEADDR
        }
    }

    /**
     * 将 Socket 绑定到指定端口 (IPv4 0.0.0.0)。
     * <p>
     * 构建 {@code sockaddr_in} 结构体并调用 {@code bind}。
     *
     * @param port 监听端口号
     * @throws IOException 如果端口已被占用或无权限
     */
    public void bind(int port) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(NativeConstants.SOCKADDR_IN_LAYOUT);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // AF_INET
            addr.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port));
            addr.set(ValueLayout.JAVA_INT, 4, 0); // INADDR_ANY (0.0.0.0)
            NativeLib.bindOrThrow(fd, addr, (int) addr.byteSize());
        }
    }

    /**
     * 开启监听模式。
     *
     * @param backlog 等待连接队列的最大长度 (对应 TCP SYN 队列/Accept 队列)
     * @throws IOException 如果 listen 调用失败
     */
    public void listen(int backlog) throws IOException {
        NativeLib.listenOrThrow(fd, backlog);
    }

    /**
     * 发起连接到指定 IP 和端口。
     * <p>
     * 这是一个阻塞调用（除非 fd 被设置为非阻塞）。
     *
     * @param ip   目标 IPv4 地址字符串 (如 "127.0.0.1")
     * @param port 目标端口
     * @return {@code true} 如果连接成功 (返回 0)，否则 {@code false}
     */
    public boolean connect(String ip, int port) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sa = arena.allocate(16); // sizeof(sockaddr_in) = 16
            sa.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // AF_INET
            sa.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port));
            // inet_addr 需要 C string (自动添加 '\0')，将 Java String 转换为 native segment
            sa.set(ValueLayout.JAVA_INT, 4, NativeLib.inet_addr(arena.allocateFrom(ip)));

            return NativeLib.connect(fd, sa, 16) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 关闭 Socket 文件描述符。
     * <p>
     * 释放操作系统内核资源。此方法通过 {@code AutoCloseable} 支持 try-with-resources 语法。
     */
    @Override
    public void close() {
        NativeLib.close(fd);
    }

    /**
     * 内部静态助手类：通过 FFM Linker 加载和调用 libc 函数。
     * <p>
     * 使用 {@link Linker#downcallHandle} 创建方法句柄，支持捕获 native errno。
     * 所有方法都使用 {@link Arena#ofConfined()} 进行线程封闭的临时内存分配，确保无内存泄漏。
     */
    private static class NativeLib {
        static final Linker L = Linker.nativeLinker();
        static final SymbolLookup SL = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

        // --- MethodHandle 定义 ---
        static final MethodHandle SOCKET = L.downcallHandle(SL.find("socket").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        static final MethodHandle LISTEN = L.downcallHandle(SL.find("listen").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle HTONS = L.downcallHandle(SL.find("htons").get(), FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));
        static final MethodHandle INET_ADDR = L.downcallHandle(SL.find("inet_addr").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        static final MethodHandle CONNECT = L.downcallHandle(SL.find("connect").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle CLOSE = L.downcallHandle(SL.find("close").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        static final MethodHandle BIND = L.downcallHandle(SL.find("bind").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle SETSOCKOPT = L.downcallHandle(SL.find("setsockopt").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));

        // 用于捕获 errno 的内存布局
        static final StructLayout CAPTURE = MemoryLayout.structLayout(ValueLayout.JAVA_INT.withName("errno"));
        static final VarHandle VH_ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        // --- Wrapper Methods with Error Handling ---

        static int socketOrThrow(int d, int t, int p) throws IOException { try { int fd = (int)SOCKET.invokeExact(d,t,p); if(fd < 0) throw new IOException("socket fail"); return fd; } catch (Throwable e) { throw new IOException(e); } }

        static void listenOrThrow(int f, int b) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)LISTEN.invokeExact(e,f,b)<0) throw new IOException("listen fail: " + VH_ERRNO.get(e,0L));
            } catch (Throwable t){ throw (IOException)t; }
        }

        static short htons(short p) { try{return(short)HTONS.invokeExact(p);}catch(Throwable e){return 0;} }

        static int inet_addr(MemorySegment ip) { try{return (int)INET_ADDR.invokeExact(ip);}catch(Throwable e){return 0;} }

        static int connect(int f, MemorySegment addr, int len) {
            try(Arena a=Arena.ofConfined()){
                MemorySegment e=a.allocate(CAPTURE);
                return (int)CONNECT.invokeExact(e, f, addr, len);
            }catch(Throwable t){ return -1; }
        }

        static void close(int f) { try{CLOSE.invokeExact(f);}catch(Throwable e){} }

        static void bindOrThrow(int f, MemorySegment a, int l) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)BIND.invokeExact(e,f,a,l)<0) throw new IOException("bind fail: "+VH_ERRNO.get(e, 0L));
            } catch(Throwable t){ if(t instanceof IOException) throw (IOException)t; }
        }

        static void setsockoptOrThrow(int f, int l, int n, MemorySegment v, int len) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)SETSOCKOPT.invokeExact(e,f,l,n,v,len)<0) throw new IOException("setsockopt fail: "+VH_ERRNO.get(e, 0L));
            } catch(Throwable t){ if(t instanceof IOException) throw (IOException)t; }
        }
    }
}