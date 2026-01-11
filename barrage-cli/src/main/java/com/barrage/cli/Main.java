package com.barrage.cli;

import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.protocol.HTTP.HttpMessage;
import com.barrage.protocol.datasource.DataSource;
import com.barrage.protocol.datasource.FileDataSource;

import static com.barrage.kernel.config.GlobalConfig.*;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Barrage Kernel 的主程序入口类。
 * <p>
 * 该类负责编排整个高性能网络引擎的生命周期，包括：
 * <ol>
 * <li>启动 GC 探测器以验证 Zero-GC 承诺。</li>
 * <li>初始化并启动基于 IoUring 的服务端引擎 (ServerEngine)。</li>
 * <li>执行预热阶段以允许 JIT 编译优化。</li>
 * <li>启动性能监控线程，实时输出 QPS 数据。</li>
 * <li>启动客户端负载生成器 (ClientEngine) 进行压测。</li>
 * </ol>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/10
 */
public class Main {

    /**
     * 全局 QPS (Queries Per Second) 统计计数器。
     * <p>
     * 使用 {@link LongAdder} 而非 {@link java.util.concurrent.atomic.AtomicLong}，
     * 是为了在极高并发下减少 CAS 冲突，提高计数性能。
     * 所有的 Client 线程将共同累加此计数器。
     */
    private static final LongAdder TOTAL_QPS = new LongAdder();

    /**
     * GC 探针对象。
     * <p>
     * 这是一个指向堆内存字节数组的弱引用 ({@link WeakReference})。
     * 它的作用是作为一个哨兵：如果 JVM 触发了 Garbage Collection (无论是 Minor GC 还是 Full GC)，
     * 弱引用指向的对象通常会被回收，导致 {@code GC_PROBE.get()} 返回 null。
     * <p>
     * 使用 volatile 保证多线程可见性。
     */
    private static volatile WeakReference<byte[]> GC_PROBE;

    /**
     * 应用程序主入口。
     *
     * @param args 命令行参数（当前未使用，配置主要通过 GlobalConfig 读取）
     * @throws Exception 如果服务端或客户端启动过程中发生致命错误
     */
    public static void main(String[] args) throws Exception {
        System.out.println("==============================================");
        System.out.println("   Barrage Kernel: True Zero-GC IoUring Engine");
        System.out.println("   Config: " + SERVER_THREADS + " Server Threads, " + CLIENT_THREADS + " Client Threads");
        System.out.println("==============================================");

        // 1. 启动服务端
        // 异常直接抛出导致程序退出，因为服务器启动失败没必要继续
        // ServerEngine 通常包含 netty/io_uring 的 bootstrap 逻辑
        new ServerEngine(PORT, SERVER_THREADS).start();

        // 2. 预热
        // 给予 JVM 足够的时间进行类加载和初步的 JIT 编译，
        // 避免在压测初期因冷启动导致的数据抖动。
        System.out.println(">>> Warming up (2 seconds)...");
        Thread.sleep(2000);

        DataSource requestSource = new FileDataSource("tmp/http_requests.txt");
        HttpMessage requestTemplate = HttpMessage.load(requestSource);

        // 3. 启动监控线程
        startMonitor();

        // 4. 启动客户端 (压测生成器)
        System.out.println(">>> Starting Client Load Generator...");
        // ClientEngine 负责产生高并发流量，并将请求统计写入 TOTAL_QPS
        new ClientEngine(IP, PORT, CLIENT_THREADS, TOTAL_QPS, requestTemplate).start();

        // 6. 启动GC监控
        setupGcDetector();

        // 7. 挂起主线程
        // 防止 main 方法退出导致 JVM 关闭。
        Thread.currentThread().join();
    }

    /**
     * 启动性能监控线程。
     * <p>
     * 该方法启动一个名为 "monitor" 的独立线程，每秒执行一次以下操作：
     * <ul>
     * <li>计算自启动以来的平均 QPS。</li>
     * <li>将结果打印到标准输出。</li>
     * </ul>
     * <p>
     * 注意：这里的 QPS 计算是基于总请求数除以总耗时 (Cumulative Average)，
     * 单位显示为 k/s (千次每秒)。
     */
    private static void startMonitor() {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long startCount = TOTAL_QPS.sum();

            while (true) {
                try { Thread.sleep(1000); } catch (Exception e) {}

                long nowTime = System.currentTimeMillis();
                long currentTotal = TOTAL_QPS.sum();
                long totalRequests = currentTotal - startCount;
                long elapsedSeconds = (nowTime - startTime) / 1000;

                if (elapsedSeconds > 0) {
                    long avgQps = totalRequests / elapsedSeconds / 1000; // k/s
                    System.out.printf(">>> [%ds] Avg QPS: %d k/s (Total: %d)%n",
                            elapsedSeconds, avgQps, totalRequests);
                }
            }
        }, "monitor").start();
    }

    /**
     * 设置并启动 GC 探测器。
     * <p>
     * 原理：
     * 创建一个守护线程，每 100ms 检查一次 {@link #GC_PROBE} 的引用状态。
     * 如果发现引用为 null，说明发生过 GC，此时会向标准错误流 (System.err) 打印警告，
     * 并重新分配一个新的探针以便继续监测。
     * <p>
     * 这对于验证 "Zero-GC" 架构至关重要，任何意外的内存分配导致的 GC 都会被捕获。
     */
    private static void setupGcDetector() {
        // 初始分配一个 1KB 的对象作为探针
        GC_PROBE = new WeakReference<>(new byte[1024]);

        Thread detector = new Thread(() -> {
            while (true) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }

                // 检查弱引用是否被清除
                if (GC_PROBE.get() == null) {
                    System.err.println("!!! GC DETECTED !!! This is not Zero-GC anymore.");
                    // 重置探针，继续监测后续的 GC 行为
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        });
        // 设置为守护线程，随主程序退出而退出
        detector.setDaemon(true);
        detector.start();
    }
}