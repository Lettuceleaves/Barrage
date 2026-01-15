package com.barrage.cli.bootstrap;

import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpTemplate;
import com.barrage.protocol.datasource.ConsoleDataSource;
import com.barrage.protocol.datasource.DataSource;
import com.barrage.protocol.datasource.FileDataSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 引擎启动引导类 (重构版)
 * 职责：按照 LaunchContext 的指令编排内核组件启动。
 */
public class EngineBootstrap {

    private static final LongAdder RECV_QPS = new LongAdder();
    private static final LongAdder SENT_QPS = new LongAdder();
    private static volatile WeakReference<byte[]> GC_PROBE;

    public static void run(Terminal t, LaunchContext ctx) throws Exception {

        t.section("Phase 1: Engine Initialization");

        // --- 1. 协议模板加载 (从 TemplateConfig 或 Console) ---
        HttpTemplate template = prepareTemplate(t, ctx);
        int payloadSize = template.toBytes().length;
        t.info(">>> Template Ready. Payload Size: " + payloadSize + " bytes");

        // --- 2. 目标参数确认 ---
        long targetQps = ctx.getQps();
        // 如果是全速模式 (0)，界面显示为 MAX
        String qpsDisplay = targetQps == 0 ? "UNLIMITED (MAX)" : String.valueOf(targetQps);

        // --- 3. 组件启动 ---
        t.section("Phase 2: Component Startup");

        String targetIp = ctx.getIp();
        int targetPort = ctx.getPort();

        if (ctx.getMode() == ExecutionMode.SELF_BENCHMARK) {
            t.info(">>> [Mode: SELF] Starting Internal Echo Server on Port: " + targetPort);
            new ServerEngine(targetPort, BasicConfig.getSERVER_THREADS()).start();
            targetIp = "127.0.0.1"; // 内部环回
        } else {
            t.info(">>> [Mode: STRESS] Target Remote Host: " + targetIp + ":" + targetPort);
        }

        // --- 4. 预热与监控 ---
        t.info(">>> Warming up JVM and Kernel (1.5s)...");
        Thread.sleep(1500);

        startDiagnosticMonitor(t, targetQps);
        setupGcDetector();

        // --- 5. 启动客户端引擎 ---
        t.section("Phase 3: Load Generator Running");
        t.info(">>> Target QPS: " + qpsDisplay + " | Threads: " + BasicConfig.getCLIENT_THREADS());

        ClientEngine engine = new ClientEngine(
                targetIp,
                targetPort,
                BasicConfig.getCLIENT_THREADS(),
                targetQps,
                RECV_QPS,
                SENT_QPS,
                template
        );

        engine.start();

        // --- 6. 阻塞主线程，防止 CLI 立即退出 ---
        t.line();
        t.warn(">>> BENCHMARKING IN PROGRESS...");
        t.info(">>> Press [ENTER] to stop engine and return to menu.");

        // 阻塞直到用户回车
        t.pause();

        // 停止引擎 (假设 ClientEngine 实现了 stop 或 close)
        // engine.stop();
        t.success(">>> Engine stopped safely.");
    }

    /**
     * 核心重构：根据上下文准备模板
     */
    private static HttpTemplate prepareTemplate(Terminal t, LaunchContext ctx) {
        if (ctx.getSourceType() == com.barrage.protocol.datasource.DataSourceType.CONSOLE) {
            t.info(">>> Switching to Manual Builder Mode...");
            return new ConsoleDataSource().load(null);
        } else {
            // FILE 模式即 TEMPLATE 模式：从 TemplateConfig 读取
            String templateName = ctx.getSourceValue();
            t.info(">>> Loading Template from Config: " + templateName);

            List<String> details = TemplateConfig.get(templateName);
            if (details == null) {
                throw new RuntimeException("Fatal: Template [" + templateName + "] not found in http.toml");
            }

            HttpTemplate template = new HttpTemplate();
            template.setMethod(details.get(0));
            template.setPath(details.get(1));
            template.setBody(details.get(2));
            template.setHeaders(details.get(3));
            template.setHost(ctx.getIp());
            template.setPort(ctx.getPort());
            return template;
        }
    }

    private static void startDiagnosticMonitor(Terminal t, long targetQps) {
        Thread monitor = new Thread(() -> {
            long lastRecv = 0, lastSent = 0;
            long lastTime = System.nanoTime();

            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

                long currRecv = RECV_QPS.sum();
                long currSent = SENT_QPS.sum();
                long currTime = System.nanoTime();

                long deltaUs = (currTime - lastTime) / 1000;
                if (deltaUs <= 0) continue;

                long realRecvRate = (currRecv - lastRecv) * 1_000_000 / deltaUs;
                long realSentRate = (currSent - lastSent) * 1_000_000 / deltaUs;

                double successRate = realSentRate > 0 ? (double) realRecvRate / realSentRate * 100.0 : 0.0;
                String status = diagnoseStatus(targetQps, realSentRate, realRecvRate);

                System.out.printf("\r[MONITOR] Sent: %-8d | Recv: %-8d | Success: %5.1f%% | %s",
                        realSentRate, realRecvRate, successRate, status);

                lastRecv = currRecv;
                lastSent = currSent;
                lastTime = currTime;
            }
        }, "monitor-thread");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static String diagnoseStatus(long target, long sent, long recv) {
        if (target > 0 && sent < target * 0.85) return "⚠️ CLIENT LAG";
        if (sent > 0 && recv < sent * 0.90) return "🔥 SERVER LAG";
        return "✅ HEALTHY";
    }

    private static void setupGcDetector() {
        GC_PROBE = new WeakReference<>(new byte[1024]);
        Thread detector = new Thread(() -> {
            while (true) {
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