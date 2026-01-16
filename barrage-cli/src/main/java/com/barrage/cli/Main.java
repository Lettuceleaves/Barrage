package com.barrage.cli;

import com.barrage.cli.bootstrap.EngineBootstrap;
import com.barrage.cli.interaction.pages.Banner;
import com.barrage.cli.interaction.pages.Home;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;

public class Main {

    public static void main(String[] args) {
        try (Terminal terminal = new Terminal()) {
            Banner.print(terminal);
            BasicConfig.load();

            while (true) {
                try {
                    // [核心修复]
                    // 1. 确保在进入菜单前，之前的日志全部输出完毕
                    System.out.flush();

                    // 2. 清除线程中断标记，防止 Scanner 误判
                    Thread.interrupted();

                    // 3. 尝试消耗掉输入流中残留的回车符 (Non-blocking check)
                    // 注意：这里只能做简单清理，不能调用阻塞的 read
                    if (System.in.available() > 0) {
                        System.in.read(new byte[System.in.available()]);
                    }

                    // 4. 进入主页
                    LaunchContext ctx = Home.open(terminal);

                    // 5. 执行任务
                    EngineBootstrap.run(terminal, ctx);

                    // 6. 任务结束，打印分隔符并立即进入下一次循环
                    terminal.line();
                    terminal.info(">>> Ready.");

                } catch (Exception e) {
                    terminal.error("\n[Error] " + e.getMessage());
                    // 防止死循环刷屏，仅在出错时等待
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}