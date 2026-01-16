package com.barrage.cli;

import com.barrage.cli.bootstrap.BatchBootstrap;
import com.barrage.cli.interaction.pages.Banner;
import com.barrage.cli.interaction.pages.Home;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;

/**
 * Barrage 应用程序的主入口类。
 * <p>
 * 该类负责初始化 CLI 运行环境，加载全局配置，并维护应用程序的主事件循环 (Main Loop)。
 */
public class Main {

    public static void main(String[] args) {
        // try-with-resources 确保 Terminal (以及底层的 System.in 包装) 在退出时正确处理
        try (Terminal terminal = new Terminal()) {
            Banner.print(terminal);

            // 1. 加载全局配置 (如果失败会在内部 System.exit)
            BasicConfig.load();

            // 2. 主事件循环
            while (true) {
                try {
                    // [输入流清洗]
                    // 每次循环回来前，确保之前的输出已刷盘，且输入流中没有残留的换行符
                    // 这解决了 "压测结束后自动跳过主菜单" 的常见 CLI 问题
                    System.out.flush();
                    Thread.interrupted(); // 清除中断状态

                    if (System.in.available() > 0) {
                        // 丢弃残留输入
                        long skipped = System.in.skip(System.in.available());
                    }

                    // 3. 进入主菜单
                    // Home.open 内部会处理 "单点测试"、"设置" 等子功能并自我循环
                    // 只有当用户选择 "Benchmark" 类任务或 "Exit" 时才会返回
                    LaunchContext ctx = Home.open(terminal);

                    // 4. 处理退出信号
                    if (ctx == null) {
                        terminal.info("\n>>> 👋 Bye.");
                        break; // 跳出 while(true)，触发 try-with-resources 关闭
                    }

                    // 5. 执行压测任务
                    // 只有拿到了有效的 Context (即用户选择了 Benchmark 或 Showcase)，才启动引擎
                    BatchBootstrap.run(terminal, ctx);

                    // 6. 任务结束
                    terminal.line();
                    terminal.info(">>> Ready. (Press Enter to return to menu)");

                    // 这里可以包含一个显式的 pause，或者依赖下一次 Home.open 开头的读取
                    // 但为了体验顺滑，通常直接进入下一次循环的清洗阶段

                } catch (Exception e) {
                    // [异常屏障] 捕获循环内的所有运行时异常，确保主程序不崩溃
                    terminal.error("\n[Error] " + e.getMessage());
                    e.printStackTrace(); // 调试期可以打印堆栈，生产环境可移除

                    // 防止发生死循环刷屏 (例如 System.in 损坏时)
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // 捕获 Terminal 初始化失败等致命错误
            e.printStackTrace();
            System.exit(1);
        }
    }
}