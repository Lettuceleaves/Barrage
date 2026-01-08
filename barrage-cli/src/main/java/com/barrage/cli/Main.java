package com.barrage.cli;

import com.barrage.kernel.util.DebugLogger;

/**
 * Barrage 引擎的命令行界面 (CLI) 入口点。
 * <p>
 * 此类负责初始化日志系统并启动 Barrage 主程序。
 * </p>
 */
public class Main {
    /**
     * CLI 应用的主入口。
     *
     * @param args 命令行参数。
     */
    public static void main(String[] args) {
        // 配置日志文件路径 (当前目录下)
        DebugLogger.setLogFile("/workspaces/Barrage/barrage.log");
        DebugLogger.info("Barrage Engine Starting...");
        DebugLogger.info("Version: JDK 25 & io_uring Powered");
        
        System.out.println("Barrage Engine - JDK 25 & io_uring Powered");
        System.out.println("Usage: barrage [options]");
        
        DebugLogger.info("CLI initialized successfully.");
    }
}
