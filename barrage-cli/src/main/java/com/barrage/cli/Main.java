package com.barrage.cli;

import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.GlobalConfig;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;
import com.barrage.protocol.datasource.DataSource;
import com.barrage.protocol.datasource.DataSourceFactory;
import com.barrage.protocol.datasource.DataSourceType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.Arena;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.LongAdder;

/**
 * Barrage Kernel 的主程序入口点与编排器。
 * <p>
 * 该类负责整个系统的生命周期管理，包括内存初始化、配置加载、引擎启动、JIT 预热以及运行时监控。
 * 它将底层的 {@link ClientEngine} 和 {@link ServerEngine} 组合在一起，进行闭环基准测试。
 *
 * <h2>启动流程：</h2>
 * <ol>
 * <li><b>全局内存初始化：</b> 创建跨线程共享的 {@link Arena}，作为所有堆外内存的根作用域。</li>
 * <li><b>资源加载：</b> 通过 CLI 交互选择数据源，将 HTTP 请求模版预加载到堆外内存。</li>
 * <li><b>服务端启动：</b> 初始化 {@code io_uring} 服务端 Workers。</li>
 * <li><b>JIT 预热：</b> 休眠 2 秒，等待 JVM C2 编译器优化热点代码并完成类加载。</li>
 * <li><b>监控注入：</b> 启动 QPS 统计线程和 Zero-GC 探测哨兵。</li>
 * <li><b>压力注入：</b> 启动客户端流量生成器，开始压测。</li>
 * </ol>
 *
 * <h2>零 GC 验证 (Zero-GC Verification)：</h2>
 * 内置了 {@link #setupGcDetector()} 机制，利用 {@link WeakReference} 作为“金丝雀”，
 * 实时监控 JVM 是否发生了垃圾回收。在理想的压测过程中，该探测器不应发出任何警报。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public class Main {

    private static final LongAdder TOTAL_QPS = new LongAdder();
    // 弱引用探测器，用于检测 GC 行为
    private static volatile WeakReference<byte[]> GC_PROBE;
    // 默认 HTTP 请求模版文件路径
    private static final String DEFAULT_FILE = "tmp/http_request.txt";

    /**
     * 应用程序主入口。
     *
     * @param args 命令行参数（暂未使用，配置通过 {@link GlobalConfig} 管理）
     * @throws Exception 如果发生严重的 IO 错误或内存分配失败
     */
    public static void main(String[] args) throws Exception {
        printBanner();

        // 1. 开启全局共享 Arena
        // 使用 try-with-resources 确保在程序退出时（虽然是主线程阻塞）理论上能清理资源
        // 实际上，Arena.ofShared() 允许跨线程传递内存段
        try (Arena mainArena = Arena.ofShared()) {

            // 2. 初始化用于加载模板的内存池
            MemoryArena templatePool = new MemoryArena(mainArena, 1);
            DataSourceFactory factory = new DataSourceFactory(templatePool);

            // 3. 选择并加载数据源 (内部已修复 SpotBugs 编码警告)
            DataSource source = selectDataSource(factory);
            System.out.println(">>> Loading HTTP Template from: " + source.getClass().getSimpleName());

            // 4. 加载 HTTP 请求模板到堆外内存
            HttpMessage requestTemplate = HttpMessage.load(source, mainArena);

            // 5. 启动服务端引擎 (适配 GlobalConfig Getter 调用)
            new ServerEngine(GlobalConfig.getPORT(), GlobalConfig.getSERVER_THREADS()).start();

            // 6. 执行预热 (Warm-up)
            // 这一步对于 Java 程序至关重要，它给 JIT 编译器足够的时间将字节码编译为本地机器码，
            // 并完成类的懒加载，避免在压测初期出现抖动。
            System.out.println(">>> Warming up (2 seconds) to stabilize JIT/AOT runtime...");
            Thread.sleep(2000);

            // 7. 启动监控线程与 GC 探测器
            startMonitor();
            setupGcDetector();

            // 8. 启动客户端负载生成器 (Traffic Generator)
            System.out.println(">>> Starting Client Load Generator...");
            new ClientEngine(
                    GlobalConfig.getIP(),
                    GlobalConfig.getPORT(),
                    GlobalConfig.getCLIENT_THREADS(),
                    TOTAL_QPS,
                    requestTemplate
            ).start();

            // 9. 挂起主线程，防止 Arena 关闭导致内存释放
            Thread.currentThread().join();
        }
    }

    /**
     * CLI 交互：根据用户输入选择数据源类型。
     * <p>
     * 修复了 SpotBugs 的 {@code DM_DEFAULT_ENCODING} 警告，显式指定 UTF-8 编码读取控制台输入。
     *
     * @param factory 数据源工厂
     * @return 初始化好的数据源对象
     */
    private static DataSource selectDataSource(DataSourceFactory factory) {
        // 关键修复：明确指定使用 StandardCharsets.UTF_8，避免依赖 OS 默认编码
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("\n[Data Source Selection]");
        System.out.println("1. FILE    (Path: " + DEFAULT_FILE + ")");
        System.out.println("2. CONSOLE (Manual Input)");
        System.out.print("Please select [1-2]: ");

        // 鲁棒性处理：防止管道输入/重定向时没有下一行导致异常
        if (!scanner.hasNextLine()) {
            return factory.create(DataSourceType.FILE, DEFAULT_FILE);
        }

        String choice = scanner.nextLine();
        if ("2".equals(choice.trim())) {
            return factory.create(DataSourceType.CONSOLE, "Enter HTTP Request Header: ");
        } else {
            return factory.create(DataSourceType.FILE, DEFAULT_FILE);
        }
    }

    private static void printBanner() {
        System.out.println("==============================================");
        System.out.println("   Barrage Kernel: True Zero-GC IoUring Engine");
        System.out.println("   Version: 1.0 | Java 22+ FFM API");
        System.out.println("   Config: " + GlobalConfig.getSERVER_THREADS() + " Server Threads, " + GlobalConfig.getCLIENT_THREADS() + " Client Threads");
        System.out.println("==============================================");
    }

    /**
     * 启动 QPS 性能监控线程。
     * <p>
     * 每秒计算并打印当前的平均 QPS (Queries Per Second)。
     * 使用 Daemon 线程，随主进程结束而结束。
     */
    private static void startMonitor() {
        Thread monitor = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long startCount = TOTAL_QPS.sum();

            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

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
        }, "monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 初始化 GC 探测器（金丝雀机制）。
     * <p>
     * 原理：创建一个指向堆内存对象的 {@link WeakReference}。
     * 根据 JVM 规范，弱引用在 GC 发生时会被立即回收（置为 null）。
     * 监控线程轮询该引用，一旦发现为 null，即证明 JVM 发生了 GC，说明堆外内存管理存在泄漏或不纯粹。
     */
    @SuppressFBWarnings(value = "DM_GC", justification = "Initializing probe for zero-GC contract monitoring")
    private static void setupGcDetector() {
        // 移除显式的 System.gc()，直接初始化探测器
        GC_PROBE = new WeakReference<>(new byte[1024]);

        Thread detector = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }

                // 如果探测对象被回收，说明触发了 GC
                if (GC_PROBE.get() == null) {
                    System.err.println("\n!!! GC DETECTED !!! Zero-GC contract violated.");
                    // 重新放入探测对象，继续下一次监控，防止日志刷屏
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        });
        detector.setDaemon(true);
        detector.start();
    }
}