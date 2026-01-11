package com.barrage.protocol.datasource;

import com.barrage.kernel.memory.MemoryArena;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 文件系统数据源。
 * 修复了字段引用错误、空指针风险及死代码。
 */
public class FileDataSource extends DataSource {

    private final Path path;
    private final MemoryArena pool;

    /**
     * @param filePath 文件路径
     * @param pool 内存池引用，用于 Zero-GC 加载
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public FileDataSource(String filePath, MemoryArena pool) {
        // 校验输入，防止 NP 风险
        this.path = Path.of(Objects.requireNonNull(filePath, "filePath cannot be null"));
        this.pool = Objects.requireNonNull(pool, "pool cannot be null");
    }

    @Override
    public long size() throws IOException {
        // 直接使用 Files.size 更简洁且符合 AOT 静态分析
        return Files.size(path);
    }

    @Override
    public MemorySegment load(Arena arena) throws IOException {
        // 1. 读取文件内容到临时数组（注意：此处会产生堆分配，加载后即被回收）
        // 如果文件极大，建议改用 FileChannel.map 映射到堆外，实现真正零拷贝
        byte[] bytes = Files.readAllBytes(this.path);

        // 2. 从内存池申请槽位
        int index = pool.allocate();
        MemorySegment buffer = pool.getBuffer(index);

        // 3. 修复 DLS_DEAD_LOCAL_STORE：明确 slice 的用途并执行拷贝
        // 确保 buffer 空间足够
        long copySize = Math.min(bytes.length, buffer.byteSize());
        MemorySegment destinationSlice = buffer.asSlice(0, copySize);
        destinationSlice.copyFrom(MemorySegment.ofArray(bytes));

        return buffer;
    }

    /**
     * 修复 NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE
     */
    public String getFileName() {
        Path fileNamePath = this.path.getFileName();
        // 如果 path 是根目录，getFileName() 可能返回 null
        return (fileNamePath == null) ? "root" : fileNamePath.toString();
    }
}