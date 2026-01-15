package com.barrage.cli.model;

import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * 启动上下文
 * 职责：封装用户在 UI 层面的所有选择，传递给内核引导程序。
 */
public class LaunchContext {

    private final ExecutionMode mode;     // 压测模式 (STRESS_TEST / SELF_BENCHMARK)
    private final String ip;              // 目标 IP
    private final int port;               // 目标端口
    private final DataSourceType sourceType; // 数据源类型 (FILE / CONSOLE)
    private final String sourceValue;     // 数据源参数 (Template Name / File Path)
    private final long qps;               // 限制 QPS (0 为无限制)

    /**
     * 完整构造函数
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
     * 静态工厂方法：用于用户取消操作或返回主菜单的情况
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