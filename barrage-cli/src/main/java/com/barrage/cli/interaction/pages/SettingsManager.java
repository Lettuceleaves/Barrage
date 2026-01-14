package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;

/**
 * 处理全局配置，如日志级别、并发限制、输出路径等
 */
public class SettingsManager implements Ansi {
    public static void open(Terminal t) {
        t.section(PURPLE + "Settings" + RESET);
        t.info("1. Change Log Level");
        t.info("2. Set Default Output Directory");
        t.info("3. Back to Main Menu");

        String choice = t.readOption("Select Setting", "3", "1", "2", "3");

        if ("1".equals(choice)) {
            // 处理逻辑...
        }
        // 执行完后会自动返回 Home 的 while 循环
    }
}