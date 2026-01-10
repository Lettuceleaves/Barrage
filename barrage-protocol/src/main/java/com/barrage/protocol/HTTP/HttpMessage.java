package com.barrage.protocol.HTTP;

import com.barrage.protocol.Message;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 协议消息的静态封装与内存管理类。
 * <p>
 * 该类专为高吞吐量基准测试（Benchmarking）设计，预定义了常用的 HTTP 请求和响应报文。
 * * <h3>核心优化：Zero-Allocation (零分配)</h3>
 * 为了极致的性能，本类不使用 Java 堆内存存储报文数据，而是利用 {@link Arena#global()}
 * 将报文直接分配在<b>持久的堆外内存</b>中。
 * <ul>
 * <li><b>避免拷贝：</b> 在发送数据时，可以直接将这些 {@link MemorySegment} 传递给 {@code io_uring}，
 * 无需进行 {@code String -> byte[]} 的转换，也无需从堆内拷贝到堆外。</li>
 * <li><b>避免 GC：</b> 由于数据驻留在 Global Arena 中，生命周期与 JVM 进程一致，
 * 永远不会被垃圾回收器扫描或回收，彻底消除了压测过程中的 GC 压力。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class HttpMessage extends Message {

    /**
     * 预定义的 HTTP/1.1 200 OK 响应报文。
     * <p>
     * <b>内容包含：</b>
     * <ul>
     * <li>Status: 200 OK</li>
     * <li>Connection: keep-alive (复用连接)</li>
     * <li>Body: "Hello World!\n" (13 bytes)</li>
     * </ul>
     * 该对象常驻堆外内存，供服务端 Worker 线程并发共享读取。
     */
    public static final HttpMessage RESPONSE_200 = new HttpMessage(
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 13\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "Hello World!\n"
    );

    /**
     * 预定义的 HTTP/1.1 GET 请求报文。
     * <p>
     * <b>内容包含：</b>
     * <ul>
     * <li>Method: GET /</li>
     * <li>Host: localhost</li>
     * <li>Connection: keep-alive</li>
     * </ul>
     * 该对象常驻堆外内存，供客户端 Generator 线程并发共享读取。
     */
    public static final HttpMessage REQUEST_DEFAULT = new HttpMessage(
            "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"
    );

    /**
     * 构造一个新的 HttpMessage。
     * <p>
     * 注意：此构造函数会立即申请全局堆外内存。
     *
     * @param content HTTP 报文的完整字符串内容
     */
    public HttpMessage(String content) {
        super(allocateGlobal(content));
    }

    /**
     * 在全局作用域分配堆外内存并存入数据。
     * <p>
     * 使用 {@link Arena#global()} 确保内存段在 JVM 整个生命周期内有效。
     * 这对于静态常量是安全的，因为它们只需要初始化一次且无需释放。
     *
     * @param content 字符串内容
     * @return 包含 US_ASCII 编码字节的原生内存段
     */
    private static MemorySegment allocateGlobal(String content) {
        // HTTP 协议头通常使用 US-ASCII 编码
        byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);

        // 申请全局内存，大小等于字节数组长度
        MemorySegment seg = Arena.global().allocate(bytes.length);

        // 将 Java 堆内的字节数组复制到堆外内存
        seg.copyFrom(MemorySegment.ofArray(bytes));

        return seg;
    }
}