package com.barrage.api.service;

import com.barrage.engine.BatchEngine;
import com.barrage.engine.ServerEngine;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 压测引擎生命周期管理服务（单例）。
 * <p>
 * 负责管理 {@link BatchEngine} 和 {@link ServerEngine} 的启动、停止，
 * 以及提供实时监控指标（QPS、延迟）给 API 层调用。
 * 该服务是 API 处理器与底层引擎之间的唯一桥梁。
 */
public final class EngineService {

    /** 全局单例实例 */
    private static final EngineService INSTANCE = new EngineService();

    /** 私有构造，禁止外部实例化 */
    private EngineService() {}

    /** 获取单例 */
    public static EngineService getInstance() {
        return INSTANCE;
    }

    // =============================================
    // 全局计数器（线程安全）
    // =============================================
    private final LongAdder recvTotal = new LongAdder();
    private final LongAdder sentTotal = new LongAdder();

    // =============================================
    // 引擎实例引用
    // =============================================
    private volatile BatchEngine batchEngine;
    private volatile ServerEngine serverEngine;

    // =============================================
    // 运行状态
    // =============================================
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long startTimeMs = 0;
    private volatile long totalTargetQps = 0;

    // =============================================
    // 实时 QPS 快照（由后台计划线程每秒更新）
    // =============================================
    private volatile long snapshotSentQps   = 0;
    private volatile long snapshotRecvQps   = 0;
    private volatile double snapshotAvgLatMs = 0.0;
    private volatile long lastSentTotal     = 0;
    private volatile long lastRecvTotal     = 0;
    private volatile long lastLatencyTotal  = 0;

    /** Ramping 监控线程 */
    private volatile Thread rampThread;

    /** 快照刷新线程（定时 1 秒）  */
    private volatile Thread snapshotThread;

    // =============================================
    // 公共接口
    // =============================================

    /**
     * 启动压测引擎。
     *
     * @param targetIp      目标 IP（null 则取 BasicConfig）
     * @param targetPort    目标端口（0 则取 BasicConfig）
     * @param targetQps     最大 QPS（0 表示无限制）
     * @param templateName  HTTP 模板名称（null 则取 BasicConfig 的 activeTemplateName）
     * @param selfBenchmark 是否启用自测服务端
     * @throws IllegalStateException 若引擎已在运行
     * @throws RuntimeException      若模板不存在或初始化失败
     */
    public synchronized void start(String targetIp, int targetPort,
                                   long targetQps, String templateName,
                                   boolean selfBenchmark) {
        if (running.get()) {
            throw new IllegalStateException("引擎已在运行，请先调用 /api/control/stop");
        }

        // 1. 补全参数默认值
        String ip   = (targetIp != null && !targetIp.isBlank()) ? targetIp : BasicConfig.getIP();
        int    port = (targetPort > 0) ? targetPort : BasicConfig.getPORT();
        String tpl  = (templateName != null && !templateName.isBlank())
                      ? templateName : BasicConfig.getACTIVE_TEMPLATE_NAME();

        // 2. 加载 HTTP 模板
        List<String> details = TemplateConfig.get(tpl);
        if (details == null) {
            throw new RuntimeException("HTTP 模板不存在: " + tpl);
        }
        HttpTemplate template = new HttpTemplate();
        template.setMethod(details.get(0));
        template.setPath(details.get(1));
        template.setBody(details.get(2));
        template.setHeaders(details.get(3));
        template.setHost(ip);
        template.setPort(port);

        // 3. 重置计数器与快照
        recvTotal.reset();
        sentTotal.reset();
        lastSentTotal = lastRecvTotal = lastLatencyTotal = 0;
        snapshotSentQps = snapshotRecvQps = 0;
        snapshotAvgLatMs = 0.0;
        totalTargetQps = targetQps;

        // 4. 可选：启动自测服务端
        if (selfBenchmark) {
            serverEngine = new ServerEngine(port, BasicConfig.getSERVER_THREADS());
            try {
                serverEngine.start();
            } catch (java.io.IOException e) {
                throw new RuntimeException("自测服务端启动失败: " + e.getMessage(), e);
            }
            ip = "127.0.0.1"; // 自测时强制指向本机
        }

        // 5. 启动客户端引擎
        final String finalIp = ip;
        batchEngine = new BatchEngine(
                finalIp, port,
                BasicConfig.getCLIENT_THREADS(),
                targetQps,
                recvTotal, sentTotal,
                template
        );

        running.set(true);
        startTimeMs = System.currentTimeMillis();
        batchEngine.start();

        // 6. 启动 Ramping 线程（逐步爬升 QPS）
        startRampThread();

        // 7. 启动快照刷新线程（每秒计算瞬时 QPS）
        startSnapshotThread();
    }

    /**
     * 停止压测引擎（等待最多 5 秒）。
     */
    public synchronized void stop() {
        if (!running.get()) return;

        running.set(false);

        // 中断后台线程
        interrupt(rampThread);
        interrupt(snapshotThread);

        // 并发关闭 client + server
        BatchEngine client = batchEngine;
        ServerEngine server = serverEngine;
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> { if (client != null) client.shutdown(); });
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> { if (server != null) server.shutdown(); });
        try {
            CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[EngineService] 警告：引擎关闭超时: " + e.getMessage());
        }

        batchEngine = null;
        serverEngine = null;
    }

    /**
     * 动态调整目标 QPS（引擎运行中有效）。
     */
    public void setTargetQps(long qps) {
        BatchEngine engine = batchEngine;
        if (engine != null && running.get()) {
            totalTargetQps = qps;
            engine.setCurrentTargetQps(qps);
        }
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取引擎运行状态快照（供 /api/control/status 使用）。
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("running", running.get());

        BatchEngine engine = batchEngine;
        if (engine != null && running.get()) {
            m.put("currentTargetQps", engine.getCurrentTargetQps());
            m.put("totalTargetQps",   totalTargetQps);
            m.put("sentQps",          snapshotSentQps);
            m.put("recvQps",          snapshotRecvQps);
            m.put("avgLatencyMs",     snapshotAvgLatMs);
            m.put("totalSent",        sentTotal.sum());
            m.put("totalRecv",        recvTotal.sum());
            m.put("uptimeSeconds",    (System.currentTimeMillis() - startTimeMs) / 1000);
        }

        return m;
    }

    // =============================================
    // 私有辅助方法
    // =============================================

    /** 启动 Ramping 线程：每秒递增 step 直到目标值 */
    private void startRampThread() {
        rampThread = new Thread(() -> {
            int step = BasicConfig.getSTEP();
            BatchEngine engine = batchEngine;
            while (running.get() && engine != null && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (!running.get()) break;

                long current = engine.getCurrentTargetQps();
                long next    = current + step;
                if (totalTargetQps > 0 && next >= totalTargetQps) {
                    next = totalTargetQps;
                }
                engine.setCurrentTargetQps(next);
            }
        }, "barrage-api-ramp");
        rampThread.setDaemon(true);
        rampThread.start();
    }

    /** 启动快照线程：每秒刷新瞬时 QPS 和延迟 */
    private void startSnapshotThread() {
        snapshotThread = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (!running.get()) break;

                BatchEngine engine = batchEngine;
                if (engine == null) break;

                long curSent = sentTotal.sum();
                long curRecv = recvTotal.sum();
                long curLat  = engine.getTotalLatencyMicros();

                long deltaSent = curSent - lastSentTotal;
                long deltaRecv = curRecv - lastRecvTotal;
                long deltaLat  = curLat  - lastLatencyTotal;

                snapshotSentQps  = deltaSent;
                snapshotRecvQps  = deltaRecv;
                snapshotAvgLatMs = (deltaRecv > 0) ? (double) deltaLat / deltaRecv / 1000.0 : 0.0;

                lastSentTotal  = curSent;
                lastRecvTotal  = curRecv;
                lastLatencyTotal = curLat;
            }
        }, "barrage-api-snapshot");
        snapshotThread.setDaemon(true);
        snapshotThread.start();
    }

    private static void interrupt(Thread t) {
        if (t != null) t.interrupt();
    }
}
