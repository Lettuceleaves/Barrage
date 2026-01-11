package com.barrage.kernel.io;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

public final class NativeSocket implements AutoCloseable {
    private final int fd;

    public NativeSocket() throws IOException {
        this.fd = NativeLib.socketOrThrow(2, 1, 0); // AF_INET, SOCK_STREAM
    }

    public NativeSocket(int fd) { this.fd = fd; }

    public int getFd() { return fd; }

    /**
     * 核心修复：在 bind 之前必须调用此方法
     */
    public void setReuseAddr() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opt = arena.allocate(ValueLayout.JAVA_INT, 1);
            opt.set(ValueLayout.JAVA_INT, 0, 1); // 设置为 1 表示开启

            // 1. 设置 SO_REUSEADDR (Level 1, Name 2)
            NativeLib.setsockoptOrThrow(fd, 1, 2, opt, 4);

            // 2. 设置 SO_REUSEPORT (Level 1, Name 15) - 极大提升多线程 Accept 性能
            NativeLib.setsockoptOrThrow(fd, 1, 15, opt, 4);
        }
    }

    public void bind(int port) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(NativeConstants.SOCKADDR_IN_LAYOUT);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // AF_INET
            addr.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port));
            addr.set(ValueLayout.JAVA_INT, 4, 0); // INADDR_ANY
            NativeLib.bindOrThrow(fd, addr, (int) addr.byteSize());
        }
    }

    public void listen(int backlog) throws IOException {
        NativeLib.listenOrThrow(fd, backlog);
    }

    public boolean connect(String ip, int port) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sa = arena.allocate(16);
            sa.set(ValueLayout.JAVA_SHORT, 0, (short) 2);
            sa.set(ValueLayout.JAVA_SHORT, 2, NativeLib.htons((short) port));
            sa.set(ValueLayout.JAVA_INT, 4, NativeLib.inet_addr(arena.allocateFrom(ip)));
            return NativeLib.connect(fd, sa, 16) == 0;
        } catch (Throwable t) { return false; }
    }

    @Override
    public void close() { NativeLib.close(fd); }

    private static class NativeLib {
        static final Linker L = Linker.nativeLinker();
        static final SymbolLookup SL = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

        static final MethodHandle SOCKET = L.downcallHandle(SL.find("socket").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        static final MethodHandle BIND = L.downcallHandle(SL.find("bind").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle SETSOCKOPT = L.downcallHandle(SL.find("setsockopt").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle LISTEN = L.downcallHandle(SL.find("listen").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle HTONS = L.downcallHandle(SL.find("htons").get(), FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));
        static final MethodHandle INET_ADDR = L.downcallHandle(SL.find("inet_addr").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        static final MethodHandle CONNECT = L.downcallHandle(SL.find("connect").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
        static final MethodHandle CLOSE = L.downcallHandle(SL.find("close").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        static final StructLayout CAPTURE = MemoryLayout.structLayout(ValueLayout.JAVA_INT.withName("errno"));
        static final VarHandle VH_ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

        static int socketOrThrow(int d, int t, int p) throws IOException {
            try { int fd = (int)SOCKET.invokeExact(d,t,p); if(fd < 0) throw new IOException("socket fail"); return fd; }
            catch (Throwable e) { throw new IOException(e); }
        }

        static void bindOrThrow(int f, MemorySegment a, int l) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)BIND.invokeExact(e, f, a, l) < 0) throw new IOException("bind fail: " + VH_ERRNO.get(e, 0L));
            } catch(Throwable t){ if(t instanceof IOException) throw (IOException)t; throw new IOException(t); }
        }

        static void setsockoptOrThrow(int f, int l, int n, MemorySegment v, int len) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)SETSOCKOPT.invokeExact(e, f, l, n, v, len) < 0) throw new IOException("setsockopt fail: " + VH_ERRNO.get(e, 0L));
            } catch(Throwable t){ if(t instanceof IOException) throw (IOException)t; throw new IOException(t); }
        }

        static void listenOrThrow(int f, int b) throws IOException {
            try(Arena z=Arena.ofConfined()){
                MemorySegment e=z.allocate(CAPTURE);
                if((int)LISTEN.invokeExact(e,f,b)<0) throw new IOException("listen fail: " + VH_ERRNO.get(e,0L));
            } catch (Throwable t){ throw new IOException(t); }
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
    }
}