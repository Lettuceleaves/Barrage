package com.barrage.cli;

import com.barrage.kernel.util.DebugLogger;

public class Main {
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
