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
 * 基于本地文件系统的静态数据源实现。
 * <p>
 * 该类用于从磁盘加载预定义的测试数据（如 HTTP 请求模版文件）。
 * 虽然加载过程涉及一次从磁盘到 Java 堆内存的拷贝，但由于该操作仅在系统启动的
 * "Setup Phase" 执行一次，因此不会影响后续压测阶段的 Zero-GC 特性。
 *
 * <h2>内存流转：</h2>
 * <pre>
 * Disk (File) -> Heap (byte[]) -> Off-Heap (MemorySegment in Arena)
 * </pre>
 *
 * <h2>代码质量修复：</h2>
 * 修复了 SpotBugs 报告的 {@code DLS_DEAD_LOCAL_STORE}（无效本地变量存储）
 * 和 {@code NP_NULL_ON_SOME_PATH}（潜在空指针）问题，增强了代码的鲁棒性。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public class FileDataSource extends DataSource {

    private final Path path;
    private final MemoryArena pool;

    /**
     * 构造文件数据源。
     *
     * @param filePath 目标文件的绝对或相对路径。
     * @param pool     目标内存池。数据加载后将驻留在此池中，供 {@code ClientEngine} 复用。
     * @throws NullPointerException 如果路径或内存池为 null
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Internal state requires reference to shared MemoryArena")
    public FileDataSource(String filePath, MemoryArena pool) {
        // 校验输入，防止 NP 风险，符合 AOT 编译对确定性的要求
        this.path = Path.of(Objects.requireNonNull(filePath, "filePath cannot be null"));
        this.pool = Objects.requireNonNull(pool, "pool cannot be null");
    }

    /**
     * 获取文件大小。
     *
     * @return 文件的字节数
     * @throws IOException 如果发生 I/O 错误（如文件不存在）
     */
    @Override
    public long size() throws IOException {
        // 直接使用 Files.size 更简洁且符合 AOT 静态分析
        return Files.size(path);
    }

    /**
     * 执行数据加载。
     * <p>
     * <b>注意：</b> 当前实现使用 {@link Files#readAllBytes(Path)}，会将整个文件读取到 JVM 堆中。
     * 对于通常的 HTTP 请求模版（几 KB 大小），这是完全可接受的。
     * <p>
     * <i>优化建议：</i> 如果将来需要加载 GB 级别的大型数据集，应重构为使用
     * {@link java.nio.channels.FileChannel#map} 直接映射到堆外内存，以避免堆内存溢出。
     *
     * @param arena 作用域 Arena（本实现主要依赖内部 pool，该参数保留以符合接口签名）
     * @return 包含文件内容的堆外内存段 {@link MemorySegment}
     * @throws IOException 如果读取文件失败
     */
    @Override
    public MemorySegment load(Arena arena) throws IOException {
        // 1. 读取文件内容到临时数组（注意：此处会产生堆分配，加载后即被回收）
        byte[] bytes = Files.readAllBytes(this.path);

        // 2. 从内存池申请槽位
        int index = pool.allocate();
        MemorySegment buffer = pool.getBuffer(index);

        // 3. 修复 DLS_DEAD_LOCAL_STORE：明确 slice 的用途并执行拷贝
        // 确保 buffer 空间足够容纳文件内容
        long copySize = Math.min(bytes.length, buffer.byteSize());

        // 创建一个切片视图，仅覆盖需要写入的区域，防止越界
        MemorySegment destinationSlice = buffer.asSlice(0, copySize);

        // 将堆内存数据拷贝到堆外内存
        destinationSlice.copyFrom(MemorySegment.ofArray(bytes));

        return buffer;
    }

    /**
     * 安全地获取文件名。
     * <p>
     * 修复了 SpotBugs {@code NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE} 警告。
     * 当路径指向文件系统根目录（如 "/" 或 "C:\"）时，{@link Path#getFileName()} 会返回 null。
     *
     * @return 文件名字符串，如果是根目录则返回 "root"
     */
    public String getFileName() {
        Path fileNamePath = this.path.getFileName();
        // 显式处理 null 情况
        return (fileNamePath == null) ? "root" : fileNamePath.toString();
    }
}