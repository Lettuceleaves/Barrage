package com.barrage.cli.interaction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * Barrage CLI 的标准终端交互封装工具。
 * <p>
 * 该类作为 {@code System.in} 和 {@code System.out} 的高级包装器，
 * 为上层业务逻辑提供了一套统一的、带样式的 I/O 接口。它屏蔽了底层的 {@link Scanner} 细节，
 * 并内置了健壮的错误处理和重试机制。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>健壮的输入循环 (Robust Input Loops)：</b> {@code readInt} 和 {@code readOption} 等方法
 * 内置了 `while(true)` 循环，当用户输入格式错误或非法选项时，会自动提示警告并要求重新输入，
 * 简化了调用方的校验逻辑。</li>
 * <li><b>语义化日志反馈：</b> 提供了 {@code success}, {@code warn}, {@code error} 等快捷方法，
 * 自动应用 ANSI 颜色上下文，确保交互界面的视觉一致性。</li>
 * <li><b>资源保护：</b> 实现了 {@link AutoCloseable} 接口，但特意重写了 {@code close()} 方法
 * 以防止意外关闭标准输入流 (Standard Input Stream)，支持在整个应用生命周期内复用。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 内部封装的 {@link Scanner} 不是线程安全的。鉴于 CLI 交互通常是单线程串行执行的，
 * 该设计在当前架构下是合理的。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class Terminal implements AutoCloseable {
    private final Scanner scanner;

    // ANSI 颜色常量
    private static final String RESET = "\033[0m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String BLUE = "\033[34m";

    /**
     * 初始化终端工具。
     * <p>
     * 创建一个包装了 {@code System.in} 的 Scanner 实例，强制使用 {@code UTF-8} 编码，
     * 以避免在不同操作系统（特别是 Windows/PowerShell）上出现中文乱码。
     */
    public Terminal() {
        this.scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    }

    /**
     * 打印普通信息 (标准输出)。
     * @param message 消息内容
     */
    public void info(String message) {
        System.out.println(message);
    }

    /**
     * 打印警告信息 (黄色高亮)。
     * <p>
     * 格式：{@code [WARN] message}
     * @param message 警告内容
     */
    public void warn(String message) {
        System.out.println(YELLOW + "[WARN] " + message + RESET);
    }

    /**
     * 打印错误信息 (红色高亮)。
     * <p>
     * 格式：{@code [ERROR] message}
     * @param message 错误内容
     */
    public void error(String message) {
        System.out.println(RED + "[ERROR] " + message + RESET);
    }

    /**
     * 打印成功信息 (绿色高亮)。
     * <p>
     * 格式：{@code [SUCCESS] message}
     * @param message 成功提示内容
     */
    public void success(String message) {
        System.out.println(GREEN + "[SUCCESS] " + message + RESET);
    }

    /**
     * 打印带换行符的章节标题。
     * @param title 标题文本
     */
    public void section(String title) {
        System.out.println("\n" + title);
    }

    /**
     * 打印一条水平分割线。
     */
    public void line() {
        System.out.println("--------------------------------------------------");
    }

    /**
     * 清理控制台屏幕。
     * <p>
     * 通过发送 ANSI 转义序列 {@code \033[H\033[2J} 来重置光标位置并清空可视区域。
     * <p>
     * <b>注意：</b> 此方法在不支持 ANSI 的终端（如部分 IDE 的控制台）中可能无效或显示乱码。
     */
    public void clear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    /**
     * 暂停流程并等待用户确认。
     * <p>
     * 这是一个阻塞操作，通常用于让用户有时间阅读屏幕上的信息。
     * 方法会丢弃用户输入的任何字符，直到检测到换行符。
     */
    public void pause() {
        System.out.print("\nPress Enter to continue...");
        if (scanner.hasNextLine()) {
            scanner.nextLine();
        }
    }

    /**
     * 核心基础方法：读取字符串输入。
     *
     * @param prompt       提示文案 (不包含冒号)
     * @param defaultValue 默认值，如果用户直接回车则返回此值
     * @return 用户输入的修剪过(trimmed)的字符串，或默认值
     */
    public String readString(String prompt, String defaultValue) {
        System.out.printf("%s %s[%s]%s: ", BLUE + prompt + RESET, YELLOW, defaultValue, RESET);
        String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return input.isEmpty() ? defaultValue : input;
    }

    /**
     * 读取字符串输入的别名方法。
     *
     * @see #readString(String, String)
     */
    public String ask(String prompt, String defaultValue) {
        return readString(prompt, defaultValue);
    }

    /**
     * 读取普通输入的别名方法。
     *
     * @see #readString(String, String)
     */
    public String readInput(String prompt, String defaultValue) {
        return readString(prompt, defaultValue);
    }

    /**
     * 读取整数输入 (带重试机制)。
     * <p>
     * 这是一个阻塞循环操作。如果用户输入的不是有效的整数，
     * 控制台会打印警告并要求重新输入，直到获得合法值为止。
     *
     * @param prompt       提示文案
     * @param defaultValue 默认整数值
     * @return 解析后的整数
     */
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
     * 读取长整数输入 (带重试机制)。
     * <p>
     * 逻辑同 {@link #readInt(String, int)}，适用于 QPS 等大数值场景。
     *
     * @param prompt       提示文案
     * @param defaultValue 默认长整数值
     * @return 解析后的长整数
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

    /**
     * 读取选项输入 (带白名单验证)。
     * <p>
     * 用户输入的值必须严格匹配 {@code validOptions} 中的某一项 (大小写敏感，具体取决于调用方逻辑，但此处仅做包含性检查)。
     * 如果输入不在白名单内，会持续提示错误并要求重试。
     *
     * @param prompt       提示文案
     * @param defaultValue 默认选项
     * @param validOptions 合法选项列表 (如 "1", "2", "y", "n")
     * @return 用户选中的合法选项
     */
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

    /**
     * 资源释放操作 (No-op)。
     * <p>
     * <b>特别说明：</b> 此方法故意留空，<b>不关闭</b> 底层的 {@code System.in} 流。
     * 这是因为标准输入流是全局共享资源，一旦关闭将导致整个 JVM 无法再次读取输入。
     * 该类实现 {@code AutoCloseable} 主要是为了支持 {@code try-with-resources} 语法结构，
     * 而非真正释放资源。
     */
    @Override
    public void close() {
        // System.in 通常不关闭，留空以防止 try-with-resources 意外关闭标准流
    }
}