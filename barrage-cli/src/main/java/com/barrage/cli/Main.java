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
 * Barrage Kernel 主程序入口。
 * 修复了 DM_DEFAULT_ENCODING 编码风险，并适配了动态配置的 Getter 调用。
 */
public class Main {

    private static final LongAdder TOTAL_QPS = new LongAdder();
    private static volatile WeakReference<byte[]> GC_PROBE;
    private static final String DEFAULT_FILE = "tmp/http_request.txt";

    public static void main(String[] args) throws Exception {
        printBanner();

        // 1. 开启全局共享 Arena
        try (Arena mainArena = Arena.ofShared()) {

            // 2. 初始化用于加载模板的内存池
            MemoryArena templatePool = new MemoryArena(mainArena, 1);
            DataSourceFactory factory = new DataSourceFactory(templatePool);

            // 3. 选择并加载数据源 (内部已修复编码问题)
            DataSource source = selectDataSource(factory);
            System.out.println(">>> Loading HTTP Template from: " + source.getClass().getSimpleName());

            // 4. 加载 HTTP 请求模板
            HttpMessage requestTemplate = HttpMessage.load(source, mainArena);

            // 5. 启动服务端引擎 (适配 Getter 调用)
            new ServerEngine(GlobalConfig.getPORT(), GlobalConfig.getSERVER_THREADS()).start();

            // 6. 执行预热
            System.out.println(">>> Warming up (2 seconds) to stabilize JIT/AOT runtime...");
            Thread.sleep(2000);

            // 7. 启动监控线程与 GC 探测器
            startMonitor();
            setupGcDetector();

            // 8. 启动客户端负载生成器 (适配 Getter 调用)
            System.out.println(">>> Starting Client Load Generator...");
            new ClientEngine(
                    GlobalConfig.getIP(),
                    GlobalConfig.getPORT(),
                    GlobalConfig.getCLIENT_THREADS(),
                    TOTAL_QPS,
                    requestTemplate
            ).start();

            // 9. 挂起主线程
            Thread.currentThread().join();
        }
    }

    /**
     * 根据用户输入选择数据源类型。
     * 修复了 SpotBugs 的 DM_DEFAULT_ENCODING 警告。
     */
    private static DataSource selectDataSource(DataSourceFactory factory) {
        // 关键修复：明确指定使用 StandardCharsets.UTF_8
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("\n[Data Source Selection]");
        System.out.println("1. FILE    (Path: " + DEFAULT_FILE + ")");
        System.out.println("2. CONSOLE (Manual Input)");
        System.out.print("Please select [1-2]: ");

        // 鲁棒性处理：防止没有输入时抛出异常
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
     * 监控 GC 活动。
     * 使用弱引用探测：如果弱引用被清除，说明发生了 GC。
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
                    // 重新放入探测对象，继续下一次监控
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        });
        detector.setDaemon(true);
        detector.start();
    }
}