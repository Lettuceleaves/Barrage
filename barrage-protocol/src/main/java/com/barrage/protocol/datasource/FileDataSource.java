package com.barrage.protocol.datasource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件系统数据源。
 * <p>
 * 从指定路径读取文件内容。
 *
 * @author LettuceLeaves
 * @since 2026/1/11
 */
public class FileDataSource extends DataSource {

    private final Path path;

    public FileDataSource(String filePath) {
        this.path = Path.of(filePath);
    }

    @Override
    public long size() throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.size();
        }
    }

    @Override
    public MemorySegment load(Arena arena) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            // 方式一：直接 mmap (如果不需要拼接Header，这是最高效的)
            // return channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);

            // 方式二：分配堆外内存并读取 (为了后续拼接 Header 更灵活，这里选择读取到分配的段中)
            MemorySegment segment = arena.allocate(fileSize);
            // 注意：MemorySegment 与 Channel 交互需要 ByteBuffer 视图或特定 API
            // Java 22+ 可以直接用 MemorySegment read，旧版本可能需要 mmap 后 copy
            // 这里为了通用性，我们采用 map 然后 copy 的方式，或者直接 map 返回供上层处理

            // 既然是 DataSource，我们直接返回映射的文件内存，拼接工作交给 HttpMessage 处理
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
        }
    }

    public String getFileName() {
        return path.getFileName().toString();
    }
}