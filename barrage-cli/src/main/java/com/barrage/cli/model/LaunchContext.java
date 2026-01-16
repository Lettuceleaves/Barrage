package com.barrage.cli.model;

import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * Barrage 压测任务的启动上下文对象 (DTO)。
 * <p>
 * 该类是一个不可变的值对象 (Value Object)，负责将用户在 CLI 交互层配置的
 * 各类离散参数（如 IP、端口、压测模式、QPS 限制等）打包成一个统一的上下文实体，
 * 传递给 {@code EngineBootstrap} 以初始化核心引擎。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>配置解耦：</b> 隔离了 UI 交互逻辑与底层引擎启动逻辑，使得引擎无需感知参数是如何获取的（手动输入或配置文件）。</li>
 * <li><b>不可变性 (Immutability)：</b> 所有字段均为 {@code final}，一旦构建无法修改，确保了在多线程环境下的传递安全性。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 类状态在构造后即固定，天然支持并发访问。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class LaunchContext {

    /**
     * 压测执行模式。
     * <p>
     * 决定是连接远程目标 ({@code STRESS_TEST}) 还是启动本地闭环测试 ({@code SELF_BENCHMARK})。
     */
    private final ExecutionMode mode;

    /**
     * 目标服务器 IPv4 地址或主机名。
     */
    private final String ip;

    /**
     * 目标服务器端口号 (1-65535)。
     */
    private final int port;

    /**
     * 请求数据源类型。
     * <p>
     * 决定如何解析 {@code sourceValue}（例如：是作为文件路径读取，还是作为模板名称查找）。
     */
    private final DataSourceType sourceType;

    /**
     * 数据源的具体值。
     * <p>
     * 含义取决于 {@code sourceType}，可能是模板名称 (如 "login_req") 或具体的构造指令。
     */
    private final String sourceValue;

    /**
     * 目标 QPS (Queries Per Second) 限制。
     * <p>
     * 值为 0 表示无限制 (Unbounded)，引擎将以最大吞吐量运行。
     */
    private final long qps;

    /**
     * 构建一个新的启动上下文。
     *
     * @param mode        运行模式
     * @param ip          目标 IP
     * @param port        目标端口
     * @param sourceType  数据源类型
     * @param sourceValue 数据源参数
     * @param qps         QPS 限制 (0 代表无限制)
     */
    public LaunchContext(ExecutionMode mode, String ip, int port,
                         DataSourceType sourceType, String sourceValue, long qps) {
        this.mode = mode;
        this.ip = ip;
        this.port = port;
        this.sourceType = sourceType;
        this.sourceValue = sourceValue;
        this.qps = qps;
    }

    /**
     * 创建一个表示“取消/无效”状态的上下文。
     * <p>
     * 通常用于用户在向导中选择“退出”或“返回上级”时，向调用者传递一个空对象信号。
     *
     * @return 内部字段全空的上下文实例
     */
    public static LaunchContext cancel() {
        return new LaunchContext(null, null, 0, null, null, 0);
    }

    // --- Getters ---

    public ExecutionMode getMode() { return mode; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public DataSourceType getSourceType() { return sourceType; }
    public String getSourceValue() { return sourceValue; }
    public long getQps() { return qps; }
}