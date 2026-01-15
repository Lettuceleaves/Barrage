package com.barrage.cli.bootstrap;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpTemplate;
import com.barrage.protocol.datasource.ConsoleDataSource;
import com.barrage.protocol.datasource.DataSourceType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 引擎启动引导类 (重构版)
 * 职责：按照 LaunchContext 的指令编排内核组件启动。
 */
public class EngineBootstrap implements Ansi {

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

        // 初始 QPS 设为 0，由 Monitor 逐步拉升
        ClientEngine engine = new ClientEngine(
                targetIp,
                targetPort,
                BasicConfig.getCLIENT_THREADS(),
                0, // 初始 limit 为 0
                RECV_QPS,
                SENT_QPS,
                template
        );

        // 启动监控线程
        startDiagnosticMonitor(t, engine, targetQps);
        setupGcDetector();

        // --- 5. 启动客户端引擎 ---
        t.section("Phase 3: Load Generator Running");
        t.info(">>> Target QPS: " + qpsDisplay + " | Step: " + BasicConfig.getSTEP() + "/s");

        engine.start();

        // --- 6. 阻塞主线程，防止 CLI 立即退出 ---
        t.line();
        t.warn(">>> BENCHMARKING IN PROGRESS...");
        t.info(">>> Press [ENTER] to stop engine and return to menu.");

        // 阻塞直到用户回车
        t.pause();
        t.success(">>> Engine stopped safely.");
    }

    private static HttpTemplate prepareTemplate(Terminal t, LaunchContext ctx) {
        if (ctx.getSourceType() == DataSourceType.CONSOLE) {
            t.info(">>> Switching to Manual Builder Mode...");
            return new ConsoleDataSource().load(null);
        } else {
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

    private static void startDiagnosticMonitor(Terminal t, ClientEngine engine, long totalTargetQps) {
        Thread monitor = new Thread(() -> {
            long lastRecv = 0, lastSent = 0;
            long lastTime = System.nanoTime();
            int step = BasicConfig.getSTEP();

            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

                long currRecv = RECV_QPS.sum();
                long currSent = SENT_QPS.sum();
                long currTime = System.nanoTime();

                long deltaUs = (currTime - lastTime) / 1000;
                if (deltaUs <= 0) continue;

                long realRecvRate = (currRecv - lastRecv) * 1_000_000 / deltaUs;
                long realSentRate = (currSent - lastSent) * 1_000_000 / deltaUs;

                // --- 1. 计算下一秒的目标 QPS (线性爬坡) ---
                long current = engine.getCurrentTargetQps();
                long next = current + step;

                // 封顶检查
                if (totalTargetQps > 0 && next > totalTargetQps) {
                    next = totalTargetQps;
                }

                // 更新引擎限流阀
                engine.setCurrentTargetQps(next);

                // --- 2. 状态诊断 ---
                // 传入 totalTargetQps 用于判断是否处于爬坡期
                String status = diagnoseStatus(next, totalTargetQps, realSentRate, realRecvRate);

                double successRate = realSentRate > 0 ? (double) realRecvRate / realSentRate * 100.0 : 0.0;

                // 打印日志
                System.out.printf("\r[MONITOR] Load: %-6d / %-6s | Sent: %-8d | Recv: %-8d | Success: %5.1f%% | %s",
                        next,
                        (totalTargetQps == 0 ? "MAX" : String.valueOf(totalTargetQps)),
                        realSentRate,
                        realRecvRate,
                        successRate,
                        status);

                lastRecv = currRecv;
                lastSent = currSent;
                lastTime = currTime;
            }
        }, "monitor-thread");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 状态诊断逻辑
     */
    private static String diagnoseStatus(long currentTarget, long totalTarget, long sent, long recv) {
        // 1. 优先检查错误 (客户端发不出包)
        // 容忍度 85%: 如果当前目标是 1000，实际发送少于 850 就算 Client Lag
        if (currentTarget > 0 && sent < currentTarget * 0.85) return "⚠️ CLIENT LAG";

        // 2. 检查服务端错误 (服务端回包慢)
        // 容忍度 90%: 发送 1000，接收少于 900 就算 Server Lag
        if (sent > 0 && recv < sent * 0.90) return "🔥 SERVER LAG";

        // 3. 如果无错误，检查是否处于爬坡阶段
        // 如果设定了总目标，且当前目标还没达到总目标 -> 显示爬升
        if (totalTarget > 0 && currentTarget < totalTarget) {
            return "📈 CLIMBING";
        }

        // 4. 既没报错，也到了最高点 (或无限制) -> 稳定
        return "✅ STABLE";
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