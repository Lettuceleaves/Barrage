package com.barrage.cli.bootstrap;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.cli.util.SignalGuard;
import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpTemplate;
import com.barrage.protocol.datasource.ConsoleDataSource;
import com.barrage.protocol.datasource.DataSourceType;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Barrage 压测引擎的启动引导与运行时协调器。
 * <p>
 * 该类负责桥接 CLI 交互层与核心测试引擎，管理测试任务的全生命周期。
 * 它不仅处理引擎的初始化、启动和优雅停机，还内置了一个独立的诊断监控线程，
 * 负责实时调整负载（Ramping）并评估系统健康状态。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>双层监控逻辑：</b> 将“负载控制（Control Layer）”与“视图展示（View Layer）”解耦。
 * 负载增长仅受限于目标 QPS，而系统健康状态（LAG/STABLE）则基于 0.85 的抖动容忍阈值判定。</li>
 * <li><b>优雅停机 (Graceful Shutdown)：</b> 集成 {@link SignalGuard} 处理 SIGINT (Ctrl+C)，
 * 确保在用户强制中断或任务结束时，所有 {@code io_uring} 资源和线程都能安全释放。</li>
 * <li><b>GC 探测探针：</b> 利用 {@link WeakReference} 机制检测 JVM 的 Full GC 事件，
 * 在控制台实时告警，辅助排查由此导致的延迟抖动。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程兼容 (Thread-Compatible)。</b>
 * 该类通过 {@link AtomicBoolean} 和 {@link LongAdder} 保证内部状态在 UI 线程、
 * 监控线程和 Shutdown Hook 之间的可见性。设计为单次执行流程，不应并发调用 {@code run} 方法。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class EngineBootstrap implements Ansi {

    /**
     * 实时接收 QPS 计数器 (全局累加)。
     */
    private static final LongAdder RECV_QPS = new LongAdder();

    /**
     * 实时发送 QPS 计数器 (全局累加)。
     */
    private static final LongAdder SENT_QPS = new LongAdder();

    /**
     * GC 探测探针，若该弱引用对象被回收，说明发生了 GC。
     */
    private static volatile WeakReference<byte[]> GC_PROBE;

    /**
     * 引擎运行状态标志位，控制所有后台线程的生命周期。
     */
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);

    /**
     * 停机任务的 Future，用于确保 shutdown 逻辑只执行一次且主线程能等待其完成。
     */
    private static final AtomicReference<CompletableFuture<Void>> SHUTDOWN_FUTURE = new AtomicReference<>();

    /**
     * 启动压测任务的主流程。
     * <p>
     * 该方法会阻塞当前线程，直到用户按回车停止、达到预设时间或收到系统中断信号。
     * 流程包括：资源初始化 -> 启动自测服务端(可选) -> 启动客户端引擎 -> 启动监控 -> 等待结束 -> 资源释放。
     *
     * @param t   终端交互接口，用于输出格式化日志
     * @param ctx 启动上下文，包含 IP、端口、QPS 目标等参数
     * @throws Exception 如果初始化失败或执行过程中发生未捕获异常
     */
    public static void run(Terminal t, LaunchContext ctx) throws Exception {
        RECV_QPS.reset(); SENT_QPS.reset(); IS_RUNNING.set(true);
        SHUTDOWN_FUTURE.set(new CompletableFuture<>());

        Thread mainThread = Thread.currentThread();
        AtomicReference<ClientEngine> clientRef = new AtomicReference<>();
        AtomicReference<ServerEngine> serverRef = new AtomicReference<>();

        Runnable shutdownTask = () -> {
            IS_RUNNING.set(false);
            if (SHUTDOWN_FUTURE.get().isDone()) return;

            System.out.println("\n>>> 🛑 Stopping..."); // 简化日志

            ClientEngine client = clientRef.get();
            ServerEngine server = serverRef.get();

            CompletableFuture<Void> stopClient = CompletableFuture.runAsync(() -> { if (client != null) client.shutdown(); });
            CompletableFuture<Void> stopServer = CompletableFuture.runAsync(() -> { if (server != null) server.shutdown(); });

            try {
                CompletableFuture.allOf(stopClient, stopServer).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.out.println(">>> ⚠️ Timeout.");
            }
            SHUTDOWN_FUTURE.get().complete(null);
        };

        Runnable signalAction = () -> {
            new Thread(shutdownTask, "signal-stop").start();
            mainThread.interrupt();
        };

        try (SignalGuard ignored = new SignalGuard(signalAction)) {
            t.section("Phase 1: Init");
            HttpTemplate template = prepareTemplate(t, ctx);

            String targetIp = ctx.getIp();
            if (ctx.getMode() == ExecutionMode.SELF_BENCHMARK) {
                t.info(">>> [SELF] Internal Server Port: " + ctx.getPort());
                ServerEngine server = new ServerEngine(ctx.getPort(), BasicConfig.getSERVER_THREADS());
                serverRef.set(server);
                server.start();
                targetIp = "127.0.0.1";
            }

            ClientEngine engine = new ClientEngine(targetIp, ctx.getPort(), BasicConfig.getCLIENT_THREADS(), 0, RECV_QPS, SENT_QPS, template);
            clientRef.set(engine);
            startDiagnosticMonitor(t, engine, ctx.getQps());
            setupGcDetector();

            t.section("Phase 3: Running");
            engine.start();

            t.line();
            t.warn(">>> BENCHMARKING... (Press Enter to stop)");

            try {
                t.pause();
                // 用户回车 -> 主动触发关闭
                shutdownTask.run();
            } catch (Exception e) {
                // Ctrl+C 会进入这里，不做处理，交给 SignalGuard
            }

        } finally {
            IS_RUNNING.set(false);
            if (!SHUTDOWN_FUTURE.get().isDone()) shutdownTask.run();

            try {
                // 等待后台关闭彻底完成
                SHUTDOWN_FUTURE.get().join();
            } catch (Exception _) {}

            // 确保没有残留的中断状态干扰回到主菜单
            Thread.interrupted();
        }
    }

    /**
     * 准备 HTTP 请求模板。
     * <p>
     * 根据上下文配置，从控制台交互输入或从预设的模板文件中加载请求详情（Method, Headers, Body）。
     *
     * @param t   终端接口
     * @param ctx 启动上下文
     * @return 构造好的 HTTP 模板对象
     * @throws RuntimeException 如果指定的模板名称不存在
     */
    private static HttpTemplate prepareTemplate(Terminal t, LaunchContext ctx) {
        if (ctx.getSourceType() == DataSourceType.CONSOLE) return new ConsoleDataSource().load(null);
        else {
            List<String> details = TemplateConfig.get(ctx.getSourceValue());
            if (details == null) throw new RuntimeException("Template not found");
            HttpTemplate template = new HttpTemplate();
            template.setMethod(details.get(0)); template.setPath(details.get(1));
            template.setBody(details.get(2)); template.setHeaders(details.get(3));
            template.setHost(ctx.getIp()); template.setPort(ctx.getPort());
            return template;
        }
    }

    /**
     * 启动诊断监控线程 (Diagnostic Monitor)。
     * <p>
     * 该线程作为守护线程运行，执行周期为 1 秒，承担三项关键职责：
     * <ol>
     * <li><b>数据采集：</b> 计算瞬时 QPS、延迟等核心指标。</li>
     * <li><b>引擎控制 (Control Layer)：</b> 执行线性加压 (Ramping)，每秒增加 {@code step} 负载，
     * 直到达到 {@code totalTargetQps}。此逻辑不依赖当前系统健康度，强制推高负载以测试极限。</li>
     * <li><b>视图展示 (View Layer)：</b> 根据实际发送/接收量与目标负载的比率，
     * 评估系统是否处于 LAG 状态 (阈值为 0.85)，并输出 ANSI 格式的监控日志。</li>
     * </ol>
     *
     * @param t              终端接口
     * @param engine         客户端引擎实例
     * @param totalTargetQps 用户设定的最大目标 QPS
     */
    private static void startDiagnosticMonitor(Terminal t, ClientEngine engine, long totalTargetQps) {
        Thread monitor = new Thread(() -> {
            long lastRecv = 0, lastSent = 0, lastTotalLatency = 0, lastTime = System.nanoTime();
            int step = BasicConfig.getSTEP();

            while (IS_RUNNING.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (!IS_RUNNING.get()) break;

                // ==========================================
                // 1. 数据采集 (Data Collection)
                // ==========================================
                long currRecv = RECV_QPS.sum();
                long currSent = SENT_QPS.sum();
                long currTotalLatency = engine.getTotalLatencyMicros();
                long currTime = System.nanoTime();
                long deltaUs = (currTime - lastTime) / 1000;
                if (deltaUs <= 0) continue;

                long realRecv = (currRecv - lastRecv) * 1_000_000 / deltaUs;
                long realSent = (currSent - lastSent) * 1_000_000 / deltaUs;
                double avgLat = (currRecv - lastRecv) > 0 ? (double)(currTotalLatency - lastTotalLatency) / (currRecv - lastRecv) / 1000.0 : 0.0;

                lastRecv = currRecv; lastSent = currSent; lastTotalLatency = currTotalLatency; lastTime = currTime;

                // ==========================================
                // 2. 引擎控制层 (Engine Control Layer)
                // 核心逻辑：只管加压，不关心由于性能不足导致的 LAG
                // ==========================================
                long currentLoad = engine.getCurrentTargetQps();
                long nextLoad = currentLoad + step;

                // 停止增长的唯一条件：达到用户设定的总目标 (Total Target)
                if (totalTargetQps > 0 && nextLoad > totalTargetQps) {
                    nextLoad = totalTargetQps;
                }

                // 执行变轨
                engine.setCurrentTargetQps(nextLoad);

                // ==========================================
                // 3. 视图显示层 (View/Display Layer)
                // 核心逻辑：评估当前健康度，0.85 是健康容忍度，与是否加压无关
                // ==========================================
                String healthStatus;

                // 这里的 0.85 是为了应对波动 (Jitter Tolerance)
                // 只要实际发送量能跟上预设目标的 85%，我们就认为系统是"稳"的
                if (nextLoad > 0 && realSent < nextLoad * 0.85) {
                    healthStatus = "⚠️ CLIENT LAG";
                } else if (realSent > 0 && realRecv < realSent * 0.85) {
                    healthStatus = "🔥 SERVER LAG";
                } else {
                    healthStatus = "✅ STABLE";
                }

                // 辅助状态：显示当前是在 爬坡(Climbing) 还是 保持(Holding)
                String phase = (totalTargetQps > 0 && nextLoad < totalTargetQps) ? "📈" : "🏁";

                System.out.printf("[MONITOR] %s Load:%-9d | Sent:%-9d | Recv:%-9d | Latency:%-6.2fms | %s\n",
                        phase, nextLoad, realSent, realRecv, avgLat, healthStatus);
            }
        }, "monitor-thread");
        monitor.setDaemon(true); monitor.start();
    }

    /**
     * 启动 JVM GC 探测器。
     * <p>
     * 创建一个守护线程，通过轮询检查 {@link WeakReference} 是否为空来判断是否发生了
     * Full GC 或 Major GC。一旦检测到，将在控制台输出警告。
     * 这对于排查高压测试下的“Stop-The-World”卡顿非常有效。
     */
    private static void setupGcDetector() {
        GC_PROBE = new WeakReference<>(new byte[1024]);
        Thread detector = new Thread(() -> {
            while (IS_RUNNING.get()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (GC_PROBE.get() == null) {
                    System.err.print("\n[GC-EVENT] !!! JVM GC DETECTED !!!\n");
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        }, "gc-detector");
        detector.setDaemon(true);
        detector.start();
    }
}