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
 * 该类负责整个系统的生命周期管理，支持交互式配置启动模式。
 *
 * <h2>更新说明 (v1.1)：</h2>
 * 增加了交互式网络配置，允许用户选择是否启动内置服务端，并指定目标端口和 IP。
 *
 * @author LettuceLeaves
 * @version 1.1
 */
public class Main {

    private static final LongAdder TOTAL_QPS = new LongAdder();
    private static volatile WeakReference<byte[]> GC_PROBE;
    private static final String DEFAULT_FILE = "tmp/http_request.txt";

    public static void main(String[] args) throws Exception {
        printBanner();

        // 1. 开启全局共享 Arena
        try (Arena mainArena = Arena.ofShared()) {

            // 统一创建一个 Scanner 用于所有交互，防止 System.in 被意外关闭
            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

            // 2. [新增] 交互式网络配置：决定是否启动 Server 以及端口设置
            boolean startInternalServer = configureNetwork(scanner);

            // 3. 初始化用于加载模板的内存池
            MemoryArena templatePool = new MemoryArena(mainArena, 1);
            DataSourceFactory factory = new DataSourceFactory(templatePool);

            // 4. 选择并加载数据源 (传入已有的 scanner)
            DataSource source = selectDataSource(scanner, factory);
            System.out.println(">>> Loading HTTP Template from: " + source.getClass().getSimpleName());

            // 5. 加载 HTTP 请求模板到堆外内存
            HttpMessage requestTemplate = HttpMessage.load(source, mainArena);

            // 6. [按需启动] 服务端引擎
            if (startInternalServer) {
                System.out.println(">>> Starting Internal Server on Port: " + GlobalConfig.getPORT());
                new ServerEngine(GlobalConfig.getPORT(), GlobalConfig.getSERVER_THREADS()).start();
            } else {
                System.out.println(">>> Client Only Mode. Target: " + GlobalConfig.getIP() + ":" + GlobalConfig.getPORT());
            }

            // 7. 执行预热 (Warm-up)
            System.out.println(">>> Warming up (2 seconds) to stabilize JIT/AOT runtime...");
            Thread.sleep(2000);

            // 8. 启动监控线程与 GC 探测器
            startMonitor();
            setupGcDetector();

            // 9. 启动客户端负载生成器
            System.out.println(">>> Starting Client Load Generator...");
            new ClientEngine(
                    GlobalConfig.getIP(),
                    GlobalConfig.getPORT(),
                    GlobalConfig.getCLIENT_THREADS(),
                    TOTAL_QPS,
                    requestTemplate
            ).start();

            // 10. 挂起主线程
            Thread.currentThread().join();
        }
    }

    /**
     * 交互式配置网络参数。
     * 更新 GlobalConfig 中的 IP 和 Port，并返回是否需要启动内置 Server。
     *
     * @param scanner 输入扫描器
     * @return true if internal server should be started, false otherwise
     */
    private static boolean configureNetwork(Scanner scanner) {
        System.out.println("\n[Run Mode Selection]");
        System.out.println("1. Self-Benchmark (Start Internal Server + Client)");
        System.out.println("2. Stress Test (Client Only -> Remote Target)");
        System.out.print("Select Mode [1/2] (Default 1): ");

        String modeInput = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        boolean startServer = !"2".equals(modeInput); // 默认为模式 1

        // 配置 IP (如果是纯客户端模式，需要输入目标 IP；如果是内网压测，默认为 localhost)
        if (!startServer) {
            System.out.print("Enter Target IP (Default 127.0.0.1): ");
            String ipInput = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            if (!ipInput.isEmpty()) {
                GlobalConfig.setIP(ipInput);
            }
        } else {
            // 内置服务端模式强制使用本地回环
            GlobalConfig.setIP("127.0.0.1");
        }

        // 配置端口
        int defaultPort = 8080;
        System.out.printf("Enter %s Port (Default %d): ", startServer ? "Listen" : "Target", defaultPort);
        String portInput = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        int port = defaultPort;
        if (!portInput.isEmpty()) {
            try {
                port = Integer.parseInt(portInput);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port format, using default: " + defaultPort);
            }
        }
        GlobalConfig.setPORT(port);

        return startServer;
    }

    /**
     * CLI 交互：根据用户输入选择数据源类型。
     */
    private static DataSource selectDataSource(Scanner scanner, DataSourceFactory factory) {
        System.out.println("\n[Data Source Selection]");
        System.out.println("1. FILE    (Path: " + DEFAULT_FILE + ")");
        System.out.println("2. CONSOLE (Manual Input)");
        System.out.print("Please select [1-2] (Default 1): ");

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
        System.out.println("   Version: 1.1 | Java 22+ FFM API");
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

    @SuppressFBWarnings(value = "DM_GC", justification = "Initializing probe for zero-GC contract monitoring")
    private static void setupGcDetector() {
        GC_PROBE = new WeakReference<>(new byte[1024]);

        Thread detector = new Thread(() -> {
            while (true) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                if (GC_PROBE.get() == null) {
                    System.err.println("\n!!! GC DETECTED !!! Zero-GC contract violated.");
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        });
        detector.setDaemon(true);
        detector.start();
    }
}