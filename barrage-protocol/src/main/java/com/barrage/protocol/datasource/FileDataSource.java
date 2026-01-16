package com.barrage.protocol.datasource;

import com.barrage.protocol.HTTP.HttpTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于本地文件系统的静态数据源加载策略。
 * <p>
 * 该类实现了 {@link DataSource} 接口，负责直接读取指定路径的文件内容，并将其封装为
 * {@link HttpTemplate} 对象。它通常用于加载用户预先录制好的 HTTP 请求快照 (Raw Snapshot)
 * 或包含特定二进制 Payload 的请求文件。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>原生字节加载 (Raw Byte Loading)：</b> 使用 {@link Files#readAllBytes} 一次性读取文件全量内容，
 * 不做任何字符集编码转换或格式解析。这确保了二进制文件（如 Protobuf 序列化数据或图片上传请求）能被原样发送。</li>
 * <li><b>零拷贝预备 (Zero-Copy Prep)：</b> 虽然加载过程涉及将磁盘数据读入 Java 堆内存，
 * 但生成的 {@link HttpTemplate} 随后会被引擎通过 {@code MemorySegment} 复制到堆外内存，为最终的零拷贝发送做准备。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 该类是无状态的 (Stateless)，每次调用 {@code load} 都会创建新的对象实例，不存在共享资源竞争。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class FileDataSource implements DataSource {

    /**
     * 加载指定路径的文件作为 HTTP 模板。
     * <p>
     * 该方法会阻塞当前线程直到文件读取完毕。
     *
     * @param filePath 目标文件的绝对路径或相对路径
     * @return 包含文件原生字节内容的 {@link HttpTemplate} 对象
     * @throws RuntimeException 如果文件不存在、无法读取或发生 I/O 错误 (包装了 {@link IOException})
     */
    @Override
    public HttpTemplate load(String filePath) {
        try {
            System.out.println(">>> Loading file: " + filePath);

            // 1. 读取文件
            byte[] rawData = Files.readAllBytes(Path.of(filePath));

            // 2. 返回包含原生字节的 Template
            return new HttpTemplate(rawData);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load datasource file: " + filePath, e);
        }
    }
}