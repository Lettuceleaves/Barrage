package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.*;
import com.barrage.cli.model.LaunchContext;

/**
 * Barrage CLI 的主菜单入口 (Main Dashboard)。
 * <p>
 * 该类充当应用程序的中央导航枢纽，负责展示顶级功能选项并处理用户的路由请求。
 * 它维护着应用程序的主事件循环 (Event Loop)，直到用户明确选择启动压测任务或退出程序。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>导航路由 (Navigation Routing)：</b> 根据用户输入将控制权分发至配置向导、快速启动、设置管理或文档查看器。</li>
 * <li><b>上下文传递 (Context Propagation)：</b> 作为 {@link LaunchContext} 的主要生产者，
 * 一旦子模块构建好启动上下文，该类负责将其透传回引导层 (Bootstrap)。</li>
 * <li><b>ANSI 视觉层级：</b> 使用不同颜色的 ANSI 代码区分菜单项、快捷键和说明文本，提升可读性。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 该类为无状态工具类。虽然 {@code open} 方法包含阻塞式 I/O 操作（等待用户输入），
 * 但其本身不维护任何共享可变状态。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class Home implements Ansi {

    /**
     * 打开主菜单并进入交互循环。
     * <p>
     * 该方法会阻塞当前线程，持续显示菜单并响应用户输入，直到发生以下两种情况之一：
     * <ol>
     * <li>用户选择了启动压测 (选项 1 或 2)，此时返回构造好的 {@link LaunchContext}。</li>
     * <li>用户选择了退出 (选项 7)，此时返回 {@code null}。</li>
     * </ol>
     * 对于设置、文档等辅助功能 (选项 3-6)，方法执行完毕后会自动重绘菜单（通过 {@code while(true)} 循环）。
     *
     * @param t 终端交互接口，用于绘制菜单 UI 和读取用户指令
     * @return 准备就绪的启动上下文 {@link LaunchContext}，用于后续引擎启动；
     * 如果用户选择退出程序，则返回 {@code null}
     */
    public static LaunchContext open(Terminal t) {

        while (true) {
            t.section(BLUE + "Main Menu" + RESET);
            t.info(YELLOW + "1. Benchmark Mode" + RESET + "   (Default) - Interactive Configuration Wizard");
            t.info(YELLOW + "2. Showcase Mode" + RESET + "              - Quick Start (Default Settings)");
            t.info(YELLOW + "3. Settings" + RESET + "                   - Preferences & Global Config");
            t.info(YELLOW + "4. HTTP Templates" + RESET + "             - Select Request Message");
            t.info(YELLOW + "5. Docs & Links" + RESET + "               - Project Resources");
            t.info(YELLOW + "6. Help" + RESET + "                       - Usage Guide");
            t.info(YELLOW + "7. Exit" + RESET + "                       - Quit Application");

            String choice = t.readOption("Select Option", "1",
                    "1", "2", "3", "4", "5", "6", "7");

            switch (choice) {
                case "1":
                    return BenchmarkWizard.run(t);
                case "2":
                    return ShowcaseRunner.run(t);
                case "3":
                    SettingsManager.open(t);
                    break;
                case "4":
                    TemplatePage.open(t);
                    break;
                case "5":
                    InfoViewer.showDocs(t);
                    break;
                case "6":
                    InfoViewer.showHelp(t);
                    break;
                case "7":
                    return null;
            }
        }
    }
}