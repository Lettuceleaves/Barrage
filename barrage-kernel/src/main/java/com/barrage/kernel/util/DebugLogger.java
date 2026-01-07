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
 * 一个简单、无反射、零依赖的日志工具，用于调试和测试信息的记录.
 * <p>
 * 该类设计为静态工具类，直接将日志写入指定文件.
 * 不使用任何复杂框架以确保在 Native Image 环境下的绝对稳定性.
 * </p>
 */
public class DebugLogger {

    private static final String DEFAULT_LOG_FILE = "barrage_debug.log";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Path logPath = Paths.get(DEFAULT_LOG_FILE);
    private static boolean consoleOutput = true;

    /**
     * 配置日志文件路径.
     *
     * @param filePath 日志文件路径
     */
    public static void setLogFile(String filePath) {
        logPath = Paths.get(filePath);
    }

    /**
     * 启用或禁用控制台输出 (默认开启).
     *
     * @param enabled 是否输出到控制台
     */
    public static void setConsoleOutput(boolean enabled) {
        consoleOutput = enabled;
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    public static void debug(String message) {
        log("DEBUG", message);
    }

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
            // 简单的 append 写入
            Files.writeString(logPath, logEntry + System.lineSeparator(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}
