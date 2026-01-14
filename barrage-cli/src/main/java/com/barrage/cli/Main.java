package com.barrage.cli;

import com.barrage.cli.bootstrap.EngineBootstrap;
import com.barrage.cli.interaction.pages.Home;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;

public class Main {

    public static void main(String[] args) {
        // 使用 try-with-resources 确保控制台资源正确管理
        try (Terminal terminal = new Terminal()) {

            // 1. 进入交互式主页 (Home)
            // 获取启动上下文 (包含配置参数，但不包含重资源对象)
            LaunchContext ctx = Home.open(terminal);

            // 2. 如果用户选择运行 (非退出)
            if (ctx.shouldRun()) {
                // 3. 将启动逻辑委托给 Bootstrap
                // 这里才开始真正的内存分配和引擎启动
                EngineBootstrap.run(terminal, ctx);
            }

        } catch (Exception e) {
            System.err.println("\n[FATAL ERROR] Engine crashed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}