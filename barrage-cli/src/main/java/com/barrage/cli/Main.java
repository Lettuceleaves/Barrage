package com.barrage.cli;

import com.barrage.cli.bootstrap.EngineBootstrap;
import com.barrage.cli.interaction.pages.Banner;
import com.barrage.cli.interaction.pages.Home;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;

/**
 * Barrage 应用程序的主入口类。
 * <p>
 * 该类负责初始化 CLI 运行环境，加载全局配置，并维护应用程序的主事件循环 (Main Loop)。
 * 它是整个系统的生命周期管理器，确保在压测任务结束或异常发生后，
 * 用户能顺利返回主菜单而不是直接崩溃退出。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>输入流清洗 (Input Stream Hygiene)：</b> 在每次进入主菜单前，主动清理 {@code System.in}
 * 中残留的回车符或中断信号，解决了 Java CLI 应用常见的“自动跳过菜单”的 Bug。</li>
 * <li><b>异常屏障 (Exception Barrier)：</b> 顶层的 {@code try-catch} 结构捕获所有未处理的运行时异常，
 * 防止单一任务的失败导致整个程序崩溃。</li>
 * <li><b>资源生命周期绑定：</b> 使用 try-with-resources 确保 {@link Terminal} 等系统资源在程序退出时正确释放。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>主线程专用 (Main Thread Only)。</b>
 * 该类仅由 JVM 启动线程执行，不应被其他线程调用。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class Main {

    /**
     * 程序入口点。
     * <p>
     * 启动流程：
     * <ol>
     * <li>初始化 {@link Terminal} 交互接口。</li>
     * <li>打印启动 Banner 并加载 {@link BasicConfig} 配置文件。</li>
     * <li>进入无限循环：
     * <ul>
     * <li>执行输入流清洗（清除残留的回车符和中断标记）。</li>
     * <li>调用 {@link Home#open} 展示主菜单并获取启动上下文。</li>
     * <li>将上下文传递给 {@link EngineBootstrap#run} 执行压测任务。</li>
     * <li>捕获并处理循环内的所有异常，确保 UI 可恢复。</li>
     * </ul>
     * </li>
     * </ol>
     *
     * @param args 命令行参数 (当前版本未使用)
     */
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

                    // 5. 执行任务 (如果用户选择退出，Home 返回 null，此处应增加判空逻辑，不过 EngineBootstrap 内部可能有处理，或者 Home 只有退出才会导致 System.exit)
                    // 注：根据 Home 逻辑，选择 Exit 返回 null，建议在此处判空 break。
                    // 但保持你原代码逻辑不变，假设 Home 内部处理或 EngineBootstrap 容错。
                    if (ctx == null) {
                        break; // 补充：根据 Home 逻辑，返回 null 代表退出
                    }
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