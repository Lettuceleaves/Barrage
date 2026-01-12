package com.barrage.kernel.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Barrage Kernel 引擎的全局静态配置中心。
 * <p>
 * 该类管理网络层的所有核心参数，包括网络拓扑（IP/Port）、线程模型（并发度）
 * 以及 io_uring 的底层调优参数（队列深度、批量大小等）。
 *
 * <h2>设计说明：</h2>
 * <ul>
 * <li><b>静态单例：</b> 所有配置均为静态字段，旨在减少对象传递开销，方便各个深层组件直接读取。</li>
 * <li><b>安全访问：</b> 字段设为 private 并通过 Getter/Setter 访问，
 * 修复了 SpotBugs {@code PA_PUBLIC_PRIMITIVE_ATTRIBUTE} 风险，防止外部代码意外修改关键参数。</li>
 * <li><b>默认值：</b> 默认参数针对本地环回测试（Loopback）进行了优化。生产环境需根据 BDP（带宽延迟积）调整。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
@SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Configuration needs to be mutable for test scenarios")
public class GlobalConfig {

    // 1. 将字段设为 private，防止外部直接暴力修改
    private static String ip = "127.0.0.1";
    private static int port = 8080;

    // --- 核心配置 ---

    /** 服务端 Worker 线程数（通常建议等于 CPU 物理核心数） */
    private static int serverThreads = 2;

    /** 客户端 Worker 线程数（用于压测引擎） */
    private static int clientThreads = 2;

    /** 客户端每个 Worker 建立的连接数（连接池大小） */
    private static int connsPerClient = 8;

    /** * HTTP 流水线深度 (Pipelining Depth)。
     * <p>决定了在未收到响应前，允许连续发送多少个请求。
     * 该值越大，吞吐量越高，但会增加内存消耗。
     */
    private static int inFlight = 16;

    /** * io_uring 提交队列(SQ)和完成队列(CQ)的深度。
     * <p>必须是 2 的幂次。较大的队列可以减少 {@code io_uring_enter} 的调用频率。
     */
    private static int queueDepth = 4096;

    /** * 系统调用批处理大小。
     * <p>决定了一次 {@code io_uring_submit} 最多提交多少个 SQE，
     * 或一次 {@code peek} 最多处理多少个 CQE。用于摊薄上下文切换开销。
     */
    private static int batchSize = 1;

    /** 单次读取的缓冲区大小 (Bytes) */
    private static int readSz = 1024;

    /**
     * 原子更新目标地址和端口。
     * <p>主要用于测试环境或动态切换目标服务。
     *
     * @param newIp   新的目标 IP 地址
     * @param newPort 新的目标端口
     */
    public static synchronized void updateEndpoint(String newIp, int newPort) {
        ip = newIp;
        port = newPort;
    }

    // 2. 提供公共的 Getter 和 Setter 访问入口

    /**
     * 获取目标/监听 IP 地址。
     * @return IPv4 地址字符串
     */
    public static String getIP() { return ip; }

    /**
     * 设置目标/监听 IP 地址。
     * @param newIp IPv4 地址字符串
     */
    public static void setIP(String newIp) { ip = newIp; }

    /**
     * 获取目标/监听端口。
     * @return 端口号 (0-65535)
     */
    public static int getPORT() { return port; }

    /**
     * 设置目标/监听端口。
     * @param newPort 端口号 (0-65535)
     */
    public static void setPORT(int newPort) { port = newPort; }

    /**
     * 获取服务端 Worker 线程数。
     * @return 线程数量
     */
    public static int getSERVER_THREADS() { return serverThreads; }

    /**
     * 获取客户端 Worker 线程数。
     * @return 线程数量
     */
    public static int getCLIENT_THREADS() { return clientThreads; }

    /**
     * 获取每个客户端线程维护的 TCP 连接数。
     * @return 连接池大小
     */
    public static int getCONNS_PER_CLIENT() { return connsPerClient; }

    /**
     * 获取 HTTP 流水线并发深度 (In-Flight Requests)。
     * <p>这决定了 ClientEngine 发送窗口的大小。
     * @return 并发请求数
     */
    public static int getIN_FLIGHT() { return inFlight; }

    /**
     * 获取 io_uring 环形缓冲区大小。
     * @return 队列条目数 (Entries)
     */
    public static int getQUEUE_DEPTH() { return queueDepth; }

    /**
     * 获取系统调用批处理阈值。
     * @return 批量提交/处理的数量
     */
    public static int getBATCH_SIZE() { return batchSize; }

    /**
     * 获取 Socket 读取缓冲区大小。
     * @return 字节数
     */
    public static int getREAD_SZ() { return readSz; }

    // 如果后续需要动态修改线程数等，可以继续补充 Setter
}