package com.barrage.cli.bootstrap;

import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.engine.ClientEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.BasicConfig;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.HTTP.HttpMessage;
import com.barrage.protocol.datasource.DataSource;
import com.barrage.protocol.datasource.DataSourceFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.Arena;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.LongAdder;

public class EngineBootstrap {

    // 两个核心计数器
    private static final LongAdder RECV_QPS = new LongAdder(); // 实际响应
    private static final LongAdder SENT_QPS = new LongAdder(); // 实际发送

    private static volatile WeakReference<byte[]> GC_PROBE;

    public static void run(Terminal t, LaunchContext ctx) throws Exception {
        try (Arena mainArena = Arena.ofShared()) {

            t.section("Engine Initialization");

            // --- 1. 加载模板 ---
            MemoryArena templatePool = new MemoryArena(mainArena, 1);
            DataSourceFactory factory = new DataSourceFactory(templatePool);
            DataSource source = factory.create(ctx.sourceType(), ctx.sourceValue());

            t.info(">>> Loading Template from " + ctx.sourceType() + "...");
            HttpMessage loadedTemplate = HttpMessage.load(source, mainArena);
            String rawContent = loadedTemplate.segment().getString(0).trim();

            if (!rawContent.endsWith("\r\n\r\n")) {
                rawContent += "\r\n\r\n";
            }
            HttpMessage requestTemplate = new HttpMessage(rawContent);
            t.info(">>> Template Validated. Size: " + requestTemplate.length() + " bytes");

            // --- 2. 交互式获取 Target QPS ---
            long targetQps = 0;
            if (ctx.mode() == ExecutionMode.SELF_BENCHMARK) {
                targetQps = 5_000_000; // 自测模式默认拉满
            } else {
                // 如果是 Client 模式，询问用户希望的发包速度
                String input = t.ask("Target QPS (Send Rate)", "50000");
                try {
                    targetQps = Long.parseLong(input);
                } catch (NumberFormatException e) {
                    targetQps = 50000;
                }
            }

            // --- 3. 启动组件 ---
            String finalTargetIp = ctx.targetIp();
            if (ctx.mode() == ExecutionMode.SELF_BENCHMARK) {
                t.info(">>> [Mode: SELF] Starting Internal Server on Port: " + ctx.targetPort());
                new ServerEngine(ctx.targetPort(), BasicConfig.getSERVER_THREADS()).start();
                finalTargetIp = "127.0.0.1";
            } else {
                t.info(">>> [Mode: STRESS] Target: " + finalTargetIp + ":" + ctx.targetPort());
            }

            // --- 4. 预热与监控 ---
            int threads = BasicConfig.getCLIENT_THREADS();
            t.info(">>> Warming up (2 seconds)...");
            Thread.sleep(2000);

            // 启动诊断监控
            startDiagnosticMonitor(t, targetQps);
            setupGcDetector(t);

            // --- 5. 启动定速客户端 ---
            t.section("Load Generator Started (Target: " + targetQps + " req/s)");

            new ClientEngine(
                    finalTargetIp,
                    ctx.targetPort(),
                    threads,
                    targetQps,   // 传入目标 QPS
                    RECV_QPS,    // 传入接收计数器
                    SENT_QPS,    // 传入发送计数器
                    requestTemplate
            ).start();

            Thread.currentThread().join();
        }
    }

    /**
     * 诊断型监控：对比 Target vs Sent vs Recv
     */
    private static void startDiagnosticMonitor(Terminal t, long targetQps) {
        Thread monitor = new Thread(() -> {
            long lastRecv = RECV_QPS.sum();
            long lastSent = SENT_QPS.sum();
            long lastTime = System.nanoTime();

            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

                long currRecv = RECV_QPS.sum();
                long currSent = SENT_QPS.sum();
                long currTime = System.nanoTime();

                long deltaUs = (currTime - lastTime) / 1000;

                if (deltaUs > 0) {
                    long recvDiff = Math.max(0, currRecv - lastRecv);
                    long sentDiff = Math.max(0, currSent - lastSent);

                    long realRecvRate = recvDiff * 1_000_000 / deltaUs;
                    long realSentRate = sentDiff * 1_000_000 / deltaUs;

                    // 计算达标率
                    double sendLoad = targetQps > 0 ? (double) realSentRate / targetQps * 100.0 : 0.0;
                    // 计算成功率 (回包 / 发包)
                    double successRate = realSentRate > 0 ? (double) realRecvRate / realSentRate * 100.0 : 0.0;

                    String status = getStatus(targetQps, realSentRate, realRecvRate);

                    // 打印漏斗数据
                    // 格式：Target -> Sent (Load%) -> Recv (Success%) -> Diagnosis
                    System.out.printf("[MONITOR] Tgt: %-7d | Sent: %-7d (%3.0f%%) | Recv: %-7d (%3.0f%%) | %s%n",
                            targetQps, realSentRate, sendLoad, realRecvRate, successRate, status);

                    lastRecv = currRecv;
                    lastSent = currSent;
                    lastTime = currTime;
                }
            }
        }, "monitor");

        monitor.setDaemon(true);
        monitor.start();
        t.info(">>> Diagnostic Monitor started.");
    }

    // 简单的状态诊断逻辑
    private static String getStatus(long target, long sent, long recv) {
        if (sent < target * 0.9) {
            return "⚠️ CLIENT LIMIT"; // 客户端发不出包 (CPU/Net)
        }
        if (recv < sent * 0.95) {
            return "🔥 SERVER LIMIT"; // 发出去了，对面没回 (Server Overload)
        }
        return "✅ HEALTHY";      // 健康
    }

    @SuppressFBWarnings(value = "DM_GC", justification = "Zero-GC probe")
    private static void setupGcDetector(Terminal t) {
        GC_PROBE = new WeakReference<>(new byte[1024]);
        Thread detector = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (GC_PROBE.get() == null) {
                    System.err.println("\n[GC-DETECTOR] !!! GC DETECTED !!!");
                    GC_PROBE = new WeakReference<>(new byte[1024]);
                }
            }
        }, "gc-detector");
        detector.setDaemon(true);
        detector.start();
    }
}