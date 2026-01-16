package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * Barrage CLI 的快速演示模式 (Showcase) 启动器。
 * <p>
 * 该类专为演示、冒烟测试或基准线测试设计。它绕过了繁琐的参数配置流程，
 * 直接读取全局默认配置 ({@link BasicConfig})，并强制启动一个内置的 Echo Server 进行本地回环压测。
 * 它可以让用户在 1 秒内看到引擎的极限吞吐能力。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>零配置启动 (Zero-Config Launch)：</b> 自动加载内存中的默认 IP、端口和模板配置，无需人工干预。</li>
 * <li><b>内环路压测 (Loopback Stress)：</b> 强制设置模式为 {@link ExecutionMode#SELF_BENCHMARK}，
 * 在同一进程内同时运行服务端和客户端，最小化网络链路干扰，专注于测试 {@code io_uring} 的 IPC 性能。</li>
 * <li><b>极限吞吐模式：</b> 默认将 QPS 目标设定为 2,000,000 (200万)，旨在触发流控上限或 CPU 瓶颈。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 作为无状态的工具类，其方法仅读取全局配置并构建值对象，不维护私有状态。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class ShowcaseRunner implements Ansi {

    /**
     * 执行快速启动初始化流程。
     * <p>
     * 该过程包含以下步骤：
     * <ol>
     * <li><b>快照配置：</b> 从 {@link BasicConfig} 获取当前的目标地址、端口及活跃模板名称。</li>
     * <li><b>环境回显：</b> 向终端打印锁定的运行参数，提示用户当前为 "Self-Benchmark" 环境。</li>
     * <li><b>上下文构建：</b> 生成 {@link LaunchContext}，其中 QPS 被硬编码为 2,000,000 以进行饱和测试。</li>
     * </ol>
     *
     * @param t 终端交互接口，用于输出初始化日志
     * @return 预配置好的启动上下文，可直接交付给 {@code EngineBootstrap} 执行
     */
    public static LaunchContext run(Terminal t) {
        t.clear();
        t.section(BLUE + "Showcase Mode Initialization" + RESET);

        // 1. 从全局配置获取当前状态
        String ip = BasicConfig.getIP();
        int port = BasicConfig.getPORT();
        String activeTemplate = BasicConfig.getACTIVE_TEMPLATE_NAME();

        // 2. 界面回显
        t.info(">> Environment: " + YELLOW + "Self-Benchmark (Internal Server + Client)" + RESET);
        t.info(">> Target     : " + CYAN + ip + ":" + port + RESET);
        t.info(">> Template   : " + CYAN + (activeTemplate != null ? activeTemplate : "Default") + RESET);
        t.line();

        t.info(">>> " + GREEN + "Showcase parameters locked. Auto-Launching..." + RESET);

        // 3. 构建启动上下文
        // 注意：ExecutionMode.SELF_BENCHMARK 会触发内置服务器的启动
        // QPS 默认为 0 (无限制)，确保 LaunchContext 构造函数已适配 long 类型的 Qps
        return new LaunchContext(
                ExecutionMode.SELF_BENCHMARK,  // 模式：自压测模式
                ip,
                port,
                DataSourceType.FILE,           // DataSourceType.FILE 对应模板加载逻辑
                activeTemplate,                // 传递当前模板名称
                2000000L
        );
    }
}