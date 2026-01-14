package com.barrage.cli.interaction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Terminal implements AutoCloseable {
    private final Scanner scanner;
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_YELLOW = "\033[33m"; // 黄色警告

    public Terminal() {
        // 显式使用 UTF-8 防止中文乱码
        this.scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    }

    public void info(String message) {
        System.out.println(message);
    }

    public void warn(String message) {
        System.out.println(ANSI_YELLOW + "[WARN] " + message + ANSI_RESET);
    }

    public void section(String title) {
        System.out.println("\n[" + title + "]");
    }

    /**
     * 核心基础方法：读取字符串
     */
    public String readString(String prompt, String defaultValue) {
        System.out.printf("%s (Default: %s): ", prompt, defaultValue);
        String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return input.isEmpty() ? defaultValue : input;
    }

    /**
     * [新增] ask 方法
     * readString 的别名，为了适配 EngineBootstrap 中的调用
     */
    public String ask(String prompt, String defaultValue) {
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

    /**
     * [新增] 读取长整型 (用于 QPS 等大数值)
     */
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
        // 通常不关闭 System.in，以免后续无法重新读取
    }
}