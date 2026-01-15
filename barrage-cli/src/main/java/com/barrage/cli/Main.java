package com.barrage.cli;

import com.barrage.cli.bootstrap.EngineBootstrap;
import com.barrage.cli.interaction.pages.Banner;
import com.barrage.cli.interaction.pages.Home;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;

public class Main {

    public static void main(String[] args) {
        // --- 0. 引导阶段 (Bootstrap) ---
        // 必须在任何业务逻辑之前加载 TOML 配置
        // 如果 path.toml 或 config.toml 不存在，内部会 System.exit(1)

        // 使用 try-with-resources 确保控制台资源正确管理
        try (Terminal terminal = new Terminal()) {

            Banner.print(terminal);
            BasicConfig.load();

            // 1. 进入交互式主页 (Home)
            // 此时 BasicConfig 已被 TOML 填充，Home 可以安全地读取配置并展示给用户
            LaunchContext ctx = Home.open(terminal);

            EngineBootstrap.run(terminal, ctx);

        } catch (Exception e) {
            System.err.println("\n[FATAL ERROR] Engine crashed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}