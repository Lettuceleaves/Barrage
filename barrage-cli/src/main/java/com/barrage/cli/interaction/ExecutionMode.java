package com.barrage.cli.interaction;

/**
 * Barrage 引擎的运行模式枚举。
 * <p>
 * 该枚举定义了压测任务的拓扑结构，决定了 {@code EngineBootstrap} 在启动时
 * 是否需要初始化内置的服务端组件。它是连接 UI 选项与底层引擎初始化的关键标识。
 *
 * <h2>模式对比：</h2>
 * <ul>
 * <li><b>SELF_BENCHMARK:</b> 用于测试 Barrage 自身的性能极限（自产自销）。</li>
 * <li><b>STRESS_TEST:</b> 用于测试外部目标的负载能力（标准压测）。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public enum ExecutionMode {

    /**
     * 闭环自测模式 (Self-Benchmark / Loopback Mode)。
     * <p>
     * 在此模式下，Barrage 将在同一个 JVM 进程中同时启动 {@code ServerEngine} 和 {@code ClientEngine}。
     * 通信通常发生在 {@code localhost} 或内存回环接口上。
     * <p>
     * <b>主要用途：</b>
     * <ul>
     * <li><b>基准线测试 (Baseline)：</b> 验证当前机器上 {@code io_uring} 的理论极限吞吐量 (IPC 性能)，
     * 排除网络链路干扰。</li>
     * <li><b>冒烟测试 (Smoke Test)：</b> 在不依赖外部环境的情况下快速验证代码或配置的正确性。</li>
     * </ul>
     */
    SELF_BENCHMARK,

    /**
     * 外部压测模式 (Stress Test / Remote Attack Mode)。
     * <p>
     * 标准的负载生成模式。系统仅启动 {@code ClientEngine}，向用户指定的远程 IP:Port 发送高并发 HTTP 请求。
     * <p>
     * <b>主要用途：</b>
     * <ul>
     * <li><b>生产环境压测：</b> 评估第三方服务器、网关或微服务的实际承载能力。</li>
     * <li><b>全链路测试：</b> 包含网络延迟、带宽限制等真实环境因素的压力测试。</li>
     * </ul>
     */
    STRESS_TEST
}