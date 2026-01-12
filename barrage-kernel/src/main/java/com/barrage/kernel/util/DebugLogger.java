package com.barrage.kernel.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简、零依赖的同步日志工具，专为 Barrage Kernel 的内核调试设计。
 * <p>
 * 此类旨在替代重量级的 Log4j/Logback/SLF4J，以满足 GraalVM Native Image 环境下对
 * "无反射 (Reflection-Free)" 和 "无配置 (Config-Free)" 的苛刻要求。
 *
 * <h2>设计哲学与取舍：</h2>
 * <ul>
 * <li><b>原生友好：</b> 没有任何动态类加载或复杂初始化逻辑，确保 AOT 编译时的绝对稳定性。</li>
 * <li><b>即时落盘：</b> 每次日志调用都会触发文件打开、写入和刷新 (Flush)。这确保了在发生 Segfault 或 JVM 崩溃时，
 * 日志不会丢失在缓冲区中，这对调试 Native 错误至关重要。</li>
 * <li><b>同步阻塞：</b> 为了保证线程安全且不引入复杂队列，使用了 {@code synchronized} 关键字。
 * <b>警告：</b> 请勿在 {@code ClientEngine} 或 {@code ServerEngine} 的热点 IO 循环中高频调用此工具，
 * 否则会严重拖累 RPS 性能。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public class DebugLogger {

    private static final String DEFAULT_LOG_FILE = "barrage_debug.log";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // 默认输出路径
    private static Path logPath = Paths.get(DEFAULT_LOG_FILE);
    // 控制台输出开关
    private static boolean consoleOutput = true;

    /**
     * 动态配置日志文件的输出路径。
     * <p>
     * 可以在应用启动时调用此方法重定向日志文件。
     *
     * @param filePath 日志文件的绝对或相对路径（例如 "logs/kernel.log"）
     */
    public static void setLogFile(String filePath) {
        logPath = Paths.get(filePath);
    }

    /**
     * 启用或禁用控制台标准输出 (Stdout/Stderr)。
     * <p>
     * 在后台守护进程 (Daemon) 模式下，通常建议关闭控制台输出以减少 TTY 开销。
     *
     * @param enabled {@code true} 表示启用双写（文件+控制台），{@code false} 仅写文件。
     */
    public static void setConsoleOutput(boolean enabled) {
        consoleOutput = enabled;
    }

    /**
     * 记录一条 INFO 级别的消息。
     * 用于记录系统启动、配置加载等关键生命周期事件。
     *
     * @param message 要记录的消息内容。
     */
    public static void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录一条 ERROR 级别的消息。
     * 通常在捕获异常 (Exception) 或系统调用失败 (errno) 时使用。
     *
     * @param message 要记录的消息内容。
     */
    public static void error(String message) {
        log("ERROR", message);
    }

    /**
     * 记录一条 DEBUG 级别的消息。
     * 仅在排查问题时开启，生产环境应避免调用。
     *
     * @param message 要记录的消息内容。
     */
    public static void debug(String message) {
        log("DEBUG", message);
    }

    /**
     * 核心日志落盘逻辑。
     * <p>
     * 使用 {@code synchronized} 确保多线程环境下的写入安全性，防止日志内容交错。
     * 采用 {@link Files#writeString} 配合 {@code APPEND} 选项实现原子级追加。
     *
     * @param level   日志级别（如 INFO, ERROR, DEBUG）。
     * @param message 消息内容。
     */
    private static synchronized void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String threadName = Thread.currentThread().getName();
        // 格式: [时间戳] [线程名] [级别] 消息
        String logEntry = String.format("[%s] [%s] [%s] %s", timestamp, threadName, level, message);

        // 1. 控制台输出 (Stdout/Stderr 分离)
        if (consoleOutput) {
            if ("ERROR".equals(level)) {
                System.err.println(logEntry);
            } else {
                System.out.println(logEntry);
            }
        }

        // 2. 文件追加 (IO Blocking)
        try {
            // StandardOpenOption.CREATE: 不存在则创建
            // StandardOpenOption.APPEND: 追加到文件末尾
            // StandardOpenOption.WRITE: 写权限
            Files.writeString(logPath, logEntry + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // 如果日志都写不进去，通常意味着磁盘满或权限错误，降级打印到 Stderr
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}