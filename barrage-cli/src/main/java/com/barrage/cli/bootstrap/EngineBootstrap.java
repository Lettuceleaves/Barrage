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

public class EngineBootstrap implements Ansi {

    private static final LongAdder RECV_QPS = new LongAdder();
    private static final LongAdder SENT_QPS = new LongAdder();
    private static volatile WeakReference<byte[]> GC_PROBE;
    private static final AtomicBoolean IS_RUNNING = new AtomicBoolean(false);
    private static final AtomicReference<CompletableFuture<Void>> SHUTDOWN_FUTURE = new AtomicReference<>();

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
            } catch (Exception e) {}

            // 确保没有残留的中断状态干扰回到主菜单
            Thread.interrupted();
        }
    }

    // ... prepareTemplate, startDiagnosticMonitor, setupGcDetector 保持不变 ...
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