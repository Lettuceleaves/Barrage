package com.barrage.protocol.HTTP;

import com.barrage.protocol.Message;
import com.barrage.protocol.datasource.DataSource;
import com.barrage.protocol.datasource.FileDataSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 协议消息的静态封装与内存管理类。
 * <p>
 * 该类作为 HTTP 报文的载体，深度集成 Java 外来函数与内存 API (Project Panama)。
 * 核心设计目标是实现<strong>零拷贝 (Zero-Copy)</strong> 报文构建，通过将数据直接映射至 {@link Arena#global()}，
 * 使得 {@code io_uring} 等底层内核接口可以直接访问用户态内存。
 * </p>
 * <p>
 * <strong>内存模型：</strong> 所有通过本类生成的实例均持有堆外内存段 {@link MemorySegment}，
 * 其生命周期通常绑定在全局域内，以减少在高频测试场景下的 GC 回收开销。
 * </p>
 *
 * @author LettuceLeaves
 * @since 2026/1/11
 */
public class HttpMessage extends Message {

    // ==========================================
    //       核心常量定义
    // ==========================================

    /**
     * 预定义的 HTTP/1.1 200 OK 静态响应报文。
     * <p>
     * 预分配在全局堆外内存中，适用于服务端在不依赖业务逻辑时的线速回显测试。
     */
    public static final HttpMessage RESPONSE_200 = new HttpMessage(
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 13\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "Hello World!\n"
    );

    /**
     * 预定义的 HTTP/1.1 GET 请求报文模板。
     * <p>
     * 常用于客户端引擎 (ClientEngine) 发起基础连接测试或吞吐量压测。
     */
    public static final HttpMessage REQUEST_DEFAULT = new HttpMessage(
            "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"
    );

    // ==========================================
    //       构造函数
    // ==========================================

    /**
     * 私有构造函数，直接封装已分配的堆外内存段。
     *
     * @param segment 必须是已经填充好 HTTP 报文数据的堆外内存段
     */
    private HttpMessage(MemorySegment segment) {
        super(segment);
    }

    /**
     * 公共构造函数，从字符串内容构建消息。
     * <p>
     * 内部会将字符串按照 US_ASCII 编码拷贝至全局堆外内存。
     *
     * @param content 报文文本内容
     */
    public HttpMessage(String content) {
        super(allocateGlobal(content));
    }

    // ==========================================
    //       内存分配辅助方法
    // ==========================================

    /**
     * 在全局作用域分配内存并写入 ASCII 文本。
     */
    private static MemorySegment allocateGlobal(String content) {
        return allocateGlobal(content.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 在全局作用域 (Global Arena) 分配内存并执行字节拷贝。
     * <p>
     * <strong>注意：</strong> 使用 {@link Arena#global()} 分配的内存直到进程结束才会被回收，
     * 仅适用于生命周期贯穿整个压测周期的静态报文。
     *
     * @param bytes 原始字节数组
     * @return 包含数据的全局堆外内存段
     */
    private static MemorySegment allocateGlobal(byte[] bytes) {
        MemorySegment seg = Arena.global().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    // ==========================================
    //       工厂方法
    // ==========================================

    /**
     * 基于 {@link DataSource} 动态构建带响应头的 HTTP 消息。
     * <p>
     * 该方法会自动识别数据源类型，推断 MIME 类型，并将 Header 与 Body 拼接至一块连续的堆外内存中。
     * 拼接过程采用内存直接拷贝，避免了 Java 堆内的二次中转。
     * </p>
     *
     * @param source 原始数据源（如 {@link FileDataSource}）
     * @return 封装好的 HTTP 响应消息实例
     * @throws IllegalArgumentException 当文件扩展名无法识别或数据源类型不受支持时抛出
     * @throws RuntimeException 当 IO 操作异常时抛出
     */
    public static HttpMessage buildResponse(DataSource source) {
        try {
            long bodySize = source.size();

            // 1. 推断 Content-Type
            String contentType;
            if (source instanceof FileDataSource fs) {
                String fileName = fs.getFileName();
                if (fileName.endsWith(".html")) {
                    contentType = "text/html";
                } else if (fileName.endsWith(".json")) {
                    contentType = "application/json";
                } else if (fileName.endsWith(".txt")) {
                    contentType = "text/plain";
                } else {
                    throw new IllegalArgumentException("Build failed: Unsupported file extension -> " + fileName);
                }
            } else {
                throw new IllegalArgumentException("Build failed: Unknown DataSource type -> " + source.getClass().getName());
            }

            // 2. 构建 Header 字符串
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bodySize + "\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n";

            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            long totalSize = headerBytes.length + bodySize;

            // 3. 分配最终内存：分配一块足以容纳 Header + Body 的连续空间
            MemorySegment finalSegment = Arena.global().allocate(totalSize);

            // 4. 拷贝 Header
            MemorySegment.copy(MemorySegment.ofArray(headerBytes), 0, finalSegment, 0, headerBytes.length);

            // 5. 零拷贝写入 Body
            try (Arena tempArena = Arena.ofConfined()) {
                MemorySegment fileData = source.load(tempArena);
                MemorySegment.copy(fileData, 0, finalSegment, headerBytes.length, bodySize);
            }

            return new HttpMessage(finalSegment);

        } catch (IOException e) {
            throw new RuntimeException("Failed to build HTTP message from source", e);
        }
    }

    /**
     * 原始报文加载 (Raw Load)。
     * <p>
     * 不对数据源进行任何 HTTP 协议封装，直接将源内容完整读取并映射至堆外内存。
     * 该方法通常用于加载已经过预处理（包含 Header 和 Body）的原始请求文件。
     * </p>
     *
     * @param source 数据源
     * @return 封装好的原始消息实例
     * @throws RuntimeException 加载过程中发生 IO 错误
     */
    public static HttpMessage load(DataSource source, Arena arena) {
        try {
            // 1. 直接从数据源加载。
            // 如果是 FileDataSource，它返回的是 mmap 映射；
            // 如果是 ConsoleDataSource，它返回的是从 MemoryArena 借出的切片。
            MemorySegment segment = source.load(arena);

            // 2. 验证有效性
            if (segment == null || segment.byteSize() == 0) {
                throw new IOException("Loaded empty or null data segment from source.");
            }

            // 3. 直接包装并返回，实现真正的 Zero-Copy
            return new HttpMessage(segment);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load raw HTTP message from source", e);
        }
    }
}