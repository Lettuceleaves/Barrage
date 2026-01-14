package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.*;
import com.barrage.cli.model.LaunchContext;

/**
 * CLI 交互的调度中心 (Dispatcher)。
 * <p>
 * 职责：
 * 1. 打印 Banner
 * 2. 展示主菜单循环
 * 3. 将用户选择调度给具体的处理类 (Wizard/Showcase/Info/Settings)
 */
public class Home implements Ansi {

    public static LaunchContext open(Terminal t) {
        Banner.print(t);

        while (true) {
            // 打印主菜单
            t.section(BLUE + "Main Menu" + RESET);
            t.info(YELLOW + "1. Benchmark Mode" + RESET + "   (Default) - Interactive Configuration Wizard");
            t.info(YELLOW + "2. Showcase Mode" + RESET + "              - Quick Start (Default Settings)");
            t.info(YELLOW + "3. Settings" + RESET + "                   - Preferences & Global Config"); // 新增项
            t.info(YELLOW + "4. Docs & Links" + RESET + "               - Project Resources");
            t.info(YELLOW + "5. Help" + RESET + "                       - Usage Guide");
            t.info(YELLOW + "6. Exit" + RESET + "                       - Quit Application");

            // 更新了可选项列表，增加了 "6"
            String choice = t.readOption("Select Option", "1", "1", "2", "3", "4", "5", "6");

            // 调度逻辑
            switch (choice) {
                case "1":
                    return BenchmarkWizard.run(t);
                case "2":
                    return ShowcaseRunner.run(t);
                case "3":
                    SettingsManager.open(t);
                    break;
                case "4":
                    InfoViewer.showDocs(t);
                    break;
                case "5":
                    InfoViewer.showHelp(t);
                    break;
                case "6":
                    return LaunchContext.exit();
            }
        }
    }
}