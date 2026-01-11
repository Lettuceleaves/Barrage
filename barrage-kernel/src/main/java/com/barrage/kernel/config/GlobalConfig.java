package com.barrage.kernel.config;

/**
 * 引擎全局静态配置类。
 * <p>
 * 该类定义了 Barrage 引擎及其内核组件的运行参数。配置涵盖了网络、线程模型、
 * {@code io_uring} 队列特征以及内存管理等核心维度。
 * </p>
 * <p>
 * <strong>注意：</strong> 此类中的参数直接影响系统的吞吐量与 CPU/内存 占用比。
 * 建议根据目标服务器的硬件规格（如 CPU 核心数、网卡带宽）进行微调。
 * </p>
 *
 * @author LettuceLeaves
 * @since 2026/1/11
 */
public class GlobalConfig {

    /**
     * 目标服务器或监听地址的 IP。
     */
    public static final String IP = "127.0.0.1";

    /**
     * 目标服务器或监听地址的端口号。
     */
    public static final int PORT = 8080;

    // --- 核心配置 ---

    /**
     * 服务端工作线程数。
     * <p>
     * 在 Server 模式下，定义了独立运行的 {@code io_uring} 事件循环线程数量。
     * 通常设置为物理核心数的 1~2 倍。
     */
    public static final int SERVER_THREADS = 4;

    /**
     * 客户端工作线程数。
     * <p>
     * 在 Client 模式下，驱动压测流量的并发线程数。每个线程维护一个独立的内存池与 io_uring 实例。
     */
    public static final int CLIENT_THREADS = 4;

    /**
     * 每个客户端线程维护的活跃连接数。
     * <p>
     * 总并发连接数 = {@link #CLIENT_THREADS} * {@code CONNS_PER_CLIENT}。
     * 设置过大会增加内存开销及文件描述符 (FD) 的占用。
     */
    public static final int CONNS_PER_CLIENT = 1024;

    /**
     * Pipeline 深度（单连接在途请求数）。
     * <p>
     * 定义了在不等待上一个响应返回的情况下，单个连接允许连续发送的最大请求数量。
     * 增加此值可以显著提升网络利用率，但会成倍增加每个连接所需的内存缓冲区数量。
     */
    public static final int IN_FLIGHT = 4;

    /**
     * {@code io_uring} 提交队列 (SQ) 与完成队列 (CQ) 的深度。
     * <p>
     * 必须为 2 的幂次方。该值决定了内核态与用户态之间单次批量交互的能力上限。
     */
    public static final int QUEUE_DEPTH = 1024;

    /**
     * 单次从完成队列 (CQ) 中获取事件的最大批次大小。
     * <p>
     * 影响事件分发的延迟与吞吐权衡：值越大单次系统调用效率越高，但可能增加单个事件的处理延迟。
     */
    public static final int BATCH_SIZE = 2;

    /**
     * 每个读取操作申请的内存缓冲区大小（单位：字节）。
     * <p>
     * 默认设置为 16KB。该值应大于预期的 HTTP 响应头部长度，以确保单次读取即可识别协议头。
     * 设置过大会导致内存碎片和缓存行利用率下降。
     */
    public static final int READ_SZ = 16384;
}