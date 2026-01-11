package com.barrage.kernel.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * 引擎全局静态配置类。
 * 修复了 SpotBugs 的 PA_PUBLIC_PRIMITIVE_ATTRIBUTE 警告。
 */
@SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL")
public class GlobalConfig {

    // 1. 将字段设为 private，防止外部直接暴力修改
    private static String ip = "127.0.0.1";
    private static int port = 8080;

    // --- 核心配置 ---
    private static int serverThreads = 8;
    private static int clientThreads = 8;
    private static int connsPerClient = 8;
    private static int inFlight = 16;
    private static int queueDepth = 4096;
    private static int batchSize = 8;
    private static int readSz = 1024;

    /**
     * 更新地址和端口。这是之前为了修复 ST_WRITE 警告而保留的方法。
     */
    public static synchronized void updateEndpoint(String newIp, int newPort) {
        ip = newIp;
        port = newPort;
    }

    // 2. 提供公共的 Getter 和 Setter 访问入口

    public static String getIP() { return ip; }
    public static void setIP(String newIp) { ip = newIp; }

    public static int getPORT() { return port; }
    public static void setPORT(int newPort) { port = newPort; }

    public static int getSERVER_THREADS() { return serverThreads; }
    public static int getCLIENT_THREADS() { return clientThreads; }
    public static int getCONNS_PER_CLIENT() { return connsPerClient; }
    public static int getIN_FLIGHT() { return inFlight; }
    public static int getQUEUE_DEPTH() { return queueDepth; }
    public static int getBATCH_SIZE() { return batchSize; }
    public static int getREAD_SZ() { return readSz; }

    // 如果后续需要动态修改线程数等，可以继续补充 Setter
}