package com.barrage.protocol.datasource;

import com.barrage.kernel.memory.MemoryArena;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;

/**
 * 基于枚举策略的工厂类。
 */
public class DataSourceFactory {

    private final MemoryArena pool;

    // 关键修正：注解必须放在存储外部对象的构造函数上
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "MemoryArena is a shared resource by design for zero-copy efficiency")
    public DataSourceFactory(MemoryArena pool) {
        // 显式检查非空，对 AOT 编译非常友好
        this.pool = Objects.requireNonNull(pool, "MemoryArena pool cannot be null");
    }

    /**
     * 根据指定的类型和参数创建数据源。
     * @param type  数据源类型枚举 (FILE, CONSOLE 等)
     * @param param 初始化参数 (如文件路径或控制台 Prompt)
     * @return 具体的 DataSource 实现
     */
    public DataSource create(DataSourceType type, String param) {
        if (type == null) {
            throw new IllegalArgumentException("DataSourceType cannot be null");
        }
        return type.create(this.pool, param);
    }
}