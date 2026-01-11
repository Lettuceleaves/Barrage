package com.barrage.protocol.datasource;

import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpRule;

/**
 * 数据源类型定义。
 * 修复了构造参数缺失问题，确保 pool 被正确透传给具体的数据源实现。
 */
public enum DataSourceType {
    FILE {
        @Override
        public DataSource create(MemoryArena pool, String param) {
            // 修复点：传入 pool 和 param 两个参数
            return new FileDataSource(param, pool);
        }
    },
    CONSOLE {
        @Override
        public DataSource create(MemoryArena pool, String param) {
            // ConsoleDataSource 同样需要 pool 和协议规则
            return new ConsoleDataSource(pool, new HttpRule());
        }
    };

    /**
     * 创建数据源的抽象方法。
     * @param pool 内存池，用于实现 Zero-GC 数据加载
     * @param param 数据源参数（如文件路径或提示信息）
     * @return 对应的数据源实例
     */
    public abstract DataSource create(MemoryArena pool, String param);
}