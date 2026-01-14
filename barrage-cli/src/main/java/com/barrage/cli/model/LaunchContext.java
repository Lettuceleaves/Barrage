package com.barrage.cli.model;

import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * 启动上下文。
 * 增加了 ExecutionMode 以区分运行场景。
 */
public record LaunchContext(
        boolean shouldRun,
        ExecutionMode mode,          // 【新增】运行模式
        String targetIp,
        int targetPort,
        DataSourceType sourceType,
        String sourceValue
) {
    /**
     * 辅助方法：判断是否需要启动内建 Server
     */
    public boolean isInternalServerRequired() {
        return mode == ExecutionMode.SELF_BENCHMARK;
    }

    public static LaunchContext exit() {
        return new LaunchContext(false, null, null, 0, null, null);
    }
}