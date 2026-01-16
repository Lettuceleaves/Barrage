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
                long currRecv = RECV_QPS.sum(), currSent = SENT_QPS.sum();
                long currTotalLatency = engine.getTotalLatencyMicros();
                long currTime = System.nanoTime();
                long deltaUs = (currTime - lastTime) / 1000;
                if (deltaUs <= 0) continue;
                long realRecv = (currRecv - lastRecv) * 1000000 / deltaUs;
                long realSent = (currSent - lastSent) * 1000000 / deltaUs;
                double avgLat = (currRecv - lastRecv) > 0 ? (double)(currTotalLatency - lastTotalLatency) / (currRecv - lastRecv) / 1000.0 : 0.0;
                lastRecv = currRecv; lastSent = currSent; lastTotalLatency = currTotalLatency; lastTime = currTime;
                long next = engine.getCurrentTargetQps() + step;
                if (totalTargetQps > 0 && next > totalTargetQps) next = totalTargetQps;
                engine.setCurrentTargetQps(next);
                String status = "✅ STABLE";
                if (next > 0 && realSent < next * 0.85) status = "⚠️ CLIENT LAG";
                if (realSent > 0 && realRecv < realSent * 0.90) status = "🔥 SERVER LAG";
                if (totalTargetQps > 0 && next < totalTargetQps) status = "📈 CLIMBING";
                System.out.printf("[MONITOR] Load: %-6d | Sent: %-7d | Recv: %-7d | Latency: %6.2f ms | %s\n", next, realSent, realRecv, avgLat, status);
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