package com.barrage.cli.interaction;

public enum ExecutionMode {
    SELF_BENCHMARK, // 闭环自测模式：启动内建 Server + Client
    STRESS_TEST     // 外部压测模式：仅启动 Client 攻击远程目标
}