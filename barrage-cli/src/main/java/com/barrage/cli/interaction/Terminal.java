package com.barrage.cli.interaction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * 终端交互工具类
 * 扩展了成功、错误反馈以及流程控制方法
 */
public class Terminal implements AutoCloseable {
    private final Scanner scanner;

    // ANSI 颜色常量
    private static final String RESET = "\033[0m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String BLUE = "\033[34m";

    public Terminal() {
        this.scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    }

    public void info(String message) {
        System.out.println(message);
    }

    public void warn(String message) {
        System.out.println(YELLOW + "[WARN] " + message + RESET);
    }

    public void error(String message) {
        System.out.println(RED + "[ERROR] " + message + RESET);
    }

    public void success(String message) {
        System.out.println(GREEN + "[SUCCESS] " + message + RESET);
    }

    public void section(String title) {
        System.out.println("\n" + title);
    }

    public void line() {
        System.out.println("--------------------------------------------------");
    }

    /**
     * 清屏效果（通过打印换行符或 ANSI 转义序列）
     */
    public void clear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    /**
     * 暂停流程，等待用户按回车继续
     */
    public void pause() {
        System.out.print("\nPress Enter to continue...");
        if (scanner.hasNextLine()) {
            scanner.nextLine();
        }
    }

    /**
     * 核心基础方法：读取字符串
     */
    public String readString(String prompt, String defaultValue) {
        System.out.printf("%s %s[%s]%s: ", BLUE + prompt + RESET, YELLOW, defaultValue, RESET);
        String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return input.isEmpty() ? defaultValue : input;
    }

    /**
     * 别名方法，适配现有调用
     */
    public String ask(String prompt, String defaultValue) {
        return readString(prompt, defaultValue);
    }

    /**
     * 别名方法，专门用于读取普通输入
     */
    public String readInput(String prompt, String defaultValue) {
        return readString(prompt, defaultValue);
    }

    public int readInt(String prompt, int defaultValue) {
        while (true) {
            String input = readString(prompt, String.valueOf(defaultValue));
            try {
                return Integer.parseInt(input);
            } catch (NumberFormatException e) {
                warn("Invalid number format. Please enter an integer.");
            }
        }
    }

    public long readLong(String prompt, long defaultValue) {
        while (true) {
            String input = readString(prompt, String.valueOf(defaultValue));
            try {
                return Long.parseLong(input);
            } catch (NumberFormatException e) {
                warn("Invalid number format. Please enter a long integer.");
            }
        }
    }

    public String readOption(String prompt, String defaultValue, String... validOptions) {
        if (validOptions == null || validOptions.length == 0) {
            return readString(prompt, defaultValue);
        }
        Set<String> validSet = new HashSet<>(Arrays.asList(validOptions));
        while (true) {
            String input = readString(prompt, defaultValue);
            if (validSet.contains(input)) {
                return input;
            }
            warn("Invalid option. Please choose from: " + validSet);
        }
    }

    @Override
    public void close() {
        // System.in 通常不关闭
    }
}