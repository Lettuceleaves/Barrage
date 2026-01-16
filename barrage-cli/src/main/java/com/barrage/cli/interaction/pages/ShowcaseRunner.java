package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * 模式 2: 快速演示模式 (Showcase)
 * <p>
 * 职责：使用当前配置的默认值快速启动全套流程（内置 Server + Client）。
 * 逻辑：直接读取 BasicConfig 中的 Target 和 Active Template。
 */
public class ShowcaseRunner implements Ansi {

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