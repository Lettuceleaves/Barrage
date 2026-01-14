package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.BasicConfig;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * 模式 2: 快速演示模式 (使用默认值)
 */
public class ShowcaseRunner implements Ansi {

    private static final String DEFAULT_FILE = "tmp/http_request.txt";

    public static LaunchContext run(Terminal t) {
        t.section(BLUE + "Showcase Mode Initialization" + RESET);

        // 1. 定义默认值
        String ip = "127.0.0.1";
        int port = 8080;
        String filePath = DEFAULT_FILE;

        t.info(">> Target: " + YELLOW + ip + ":" + port + RESET);
        t.info(">> Mode:   " + YELLOW + "Internal Server + Client" + RESET);
        t.info(">> Data:   " + YELLOW + filePath + RESET);

        // 2. 同步全局配置
        BasicConfig.setIP(ip);
        BasicConfig.setPORT(port);

        t.info("\n>>> Defaults applied. " + GREEN + "Auto-Launching Engine..." + RESET);

        // 在 Showcase 返回时
        return new LaunchContext(
                true,
                ExecutionMode.SELF_BENCHMARK, // Showcase 模式启动全套
                "127.0.0.1",
                8080,
                DataSourceType.FILE,
                DEFAULT_FILE
        );
    }
}