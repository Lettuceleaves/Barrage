package com.barrage.kernel.config;

public class GlobalConfig {
    public static final String IP = "127.0.0.1";
    public static final int PORT = 8080;

    // --- 核心配置 ---
    public static final int SERVER_THREADS = 4;
    public static final int CLIENT_THREADS = 4;
    public static final int CONNS_PER_CLIENT = 1024;

    public static final int IN_FLIGHT = 4;
    public static final int QUEUE_DEPTH = 16384;
    public static final int BATCH_SIZE = 512;
    public static final int READ_SZ = 1024;
}