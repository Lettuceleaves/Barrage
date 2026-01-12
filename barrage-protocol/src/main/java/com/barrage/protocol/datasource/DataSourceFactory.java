package com.barrage.protocol.datasource;

import com.barrage.kernel.memory.MemoryArena;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;

/**
 * 数据源构建工厂。
 * <p>
 * 该类实现了标准工厂模式，充当协议层与底层内存管理之间的粘合剂。
 * 它采用策略模式（通过 {@link DataSourceType} 枚举），将具体的创建逻辑分发给对应的枚举实例，
 * 同时负责将核心的 {@link MemoryArena} 上下文注入到每个新创建的数据源中。
 *
 * <h2>设计目标：</h2>
 * <ul>
 * <li><b>统一入口：</b> 屏蔽不同数据源（文件、控制台、网络流）的初始化差异。</li>
 * <li><b>依赖注入：</b> 集中管理 {@code MemoryArena} 的传递，确保所有数据源都写入同一个堆外内存池，维持零拷贝特性。</li>
 * <li><b>空安全：</b> 在构造阶段执行严格的非空检查，符合 GraalVM AOT 编译的静态分析要求，防止运行时 NPE。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see DataSourceType
 */
public class DataSourceFactory {

    private final MemoryArena pool;

    /**
     * 构造数据源工厂。
     * <p>
     * 接受一个共享的内存池实例。该工厂生产的所有 {@link DataSource} 都会持有此内存池的引用，
     * 用于将外部数据（如文件内容）加载到堆外内存中。
     *
     * @param pool 共享的 {@link MemoryArena} 实例，必须非空。
     */
    // 关键修正：注解必须放在存储外部对象的构造函数上
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "MemoryArena is a shared resource by design for zero-copy efficiency")
    public DataSourceFactory(MemoryArena pool) {
        // 显式检查非空，对 AOT 编译非常友好
        this.pool = Objects.requireNonNull(pool, "MemoryArena pool cannot be null");
    }

    /**
     * 根据指定的类型和参数创建数据源实例。
     * <p>
     * 该方法将具体的实例化逻辑委托给 {@link DataSourceType#create(MemoryArena, String)} 执行，
     * 实现了工厂与具体产品实现的解耦。
     *
     * @param type  数据源类型枚举 (如 {@link DataSourceType#FILE}, {@link DataSourceType#CONSOLE})
     * @param param 初始化参数 (如文件路径字符串或控制台提示语)
     * @return 具体的 {@link DataSource} 实现类
     * @throws IllegalArgumentException 如果 type 为 null
     */
    public DataSource create(DataSourceType type, String param) {
        if (type == null) {
            throw new IllegalArgumentException("DataSourceType cannot be null");
        }
        return type.create(this.pool, param);
    }
}