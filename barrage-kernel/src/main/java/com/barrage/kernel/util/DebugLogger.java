package com.barrage.kernel.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 简单的、无反射、零依赖的日志工具，用于调试和测试。
 * <p>
 * 此类旨在作为一个静态工具，直接将日志写入指定文件。
 * 它避免使用复杂的框架，以确保在 Native Image（原生镜像）环境下的绝对稳定性。
 * </p>
 */
public class DebugLogger {

    private static final String DEFAULT_LOG_FILE = "barrage_debug.log";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Path logPath = Paths.get(DEFAULT_LOG_FILE);
    private static boolean consoleOutput = true;

    /**
     * 配置日志文件路径。
     *
     * @param filePath 日志文件的路径。
     */
    public static void setLogFile(String filePath) {
        logPath = Paths.get(filePath);
    }

    /**
     * 启用或禁用控制台输出。默认为 {@code true}。
     *
     * @param enabled {@code true} 表示启用控制台输出，{@code false} 表示禁用。
     */
    public static void setConsoleOutput(boolean enabled) {
        consoleOutput = enabled;
    }

    /**
     * 记录一条 INFO 级别的消息。
     *
     * @param message 要记录的消息内容。
     */
    public static void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录一条 ERROR 级别的消息。
     *
     * @param message 要记录的消息内容。
     */
    public static void error(String message) {
        log("ERROR", message);
    }

    /**
     * 记录一条 DEBUG 级别的消息。
     *
     * @param message 要记录的消息内容。
     */
    public static void debug(String message) {
        log("DEBUG", message);
    }

    /**
     * 核心日志逻辑。通过 synchronized 确保写入文件时的线程安全。
     *
     * @param level   日志级别（如 INFO, ERROR, DEBUG）。
     * @param message 消息内容。
     */
    private static synchronized void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String threadName = Thread.currentThread().getName();
        String logEntry = String.format("[%s] [%s] [%s] %s", timestamp, threadName, level, message);

        if (consoleOutput) {
            if ("ERROR".equals(level)) {
                System.err.println(logEntry);
            } else {
                System.out.println(logEntry);
            }
        }

        try {
            // Append log entry to file
            Files.writeString(logPath, logEntry + System.lineSeparator(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}
