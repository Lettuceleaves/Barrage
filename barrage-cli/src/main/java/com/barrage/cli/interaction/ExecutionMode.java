package com.barrage.cli.interaction;

/**
 * Barrage 引擎的运行模式枚举。
 */
public enum ExecutionMode {
    SELF_BENCHMARK, // 闭环自测
    STRESS_TEST,    // 外部压测
    SIMULATION      // [New] 模拟模式：空转引擎，验证逻辑与配置
}