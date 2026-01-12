package com.barrage.protocol.datasource;

import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpRule;

/**
 * 数据源类型策略枚举。
 * <p>
 * 该枚举实现了“策略工厂 (Strategy Factory)”模式。每个枚举实例不仅代表一种数据源类型，
 * 还包含了创建对应 {@link DataSource} 实例的具体逻辑。
 *
 * <h2>设计目的：</h2>
 * <ul>
 * <li><b>消除条件判断：</b> 避免在工厂类中使用大量的 {@code if-else} 或 {@code switch-case} 语句。</li>
 * <li><b>上下文注入：</b> 确保核心组件（如 {@link MemoryArena}）在创建过程中被强制注入，维持 Zero-GC 契约。</li>
 * <li><b>参数多态：</b> 统一了不同数据源的初始化接口，尽管它们对 {@code param} 参数的理解不同（如文件路径 vs 提示符）。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public enum DataSourceType {

    /**
     * 文件系统数据源策略。
     * <p>
     * 使用 {@code param} 作为文件路径，创建一个 {@link FileDataSource}。
     * 适用于生产环境压测，支持大文件映射。
     */
    FILE {
        @Override
        public DataSource create(MemoryArena pool, String param) {
            // 修复点：传入 pool 和 param 两个参数，确保文件内容直接读入 MemoryArena
            return new FileDataSource(param, pool);
        }
    },

    /**
     * 控制台交互数据源策略。
     * <p>
     * 创建一个 {@link ConsoleDataSource}，并默认绑定 {@link HttpRule} 协议规则。
     * 适用于开发调试和手动构造特定请求报文。
     */
    CONSOLE {
        @Override
        public DataSource create(MemoryArena pool, String param) {
            // ConsoleDataSource 同样需要 pool 来分配最终的内存段
            // 这里硬编码了 HttpRule，如果后续支持多种协议，可扩展参数
            return new ConsoleDataSource(pool, new HttpRule());
        }
    };

    /**
     * 创建具体数据源实例的抽象工厂方法。
     * <p>
     * 枚举的每个实例（FILE, CONSOLE）都必须实现此方法。
     *
     * @param pool  共享的内存池 {@link MemoryArena}，用于实现 Zero-GC 数据加载。
     * 数据源必须将加载的数据直接写入此 Pool 中。
     * @param param 数据源特定的初始化参数。
     * 对于 {@code FILE}，这是文件路径；
     * 对于 {@code CONSOLE}，这通常是提示信息（或忽略）。
     * @return 初始化完成的具体 {@link DataSource} 实例。
     */
    public abstract DataSource create(MemoryArena pool, String param);
}