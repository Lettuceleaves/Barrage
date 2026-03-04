package com.barrage.api.handler;

import com.barrage.api.service.EngineService;
import com.barrage.api.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 引擎控制 HTTP 处理器。
 * <p>
 * 挂载路径：/api/control/*
 * <ul>
 *   <li>POST /api/control/start  → 启动压测引擎</li>
 *   <li>POST /api/control/stop   → 停止压测引擎</li>
 *   <li>GET  /api/control/status → 查询引擎状态和实时指标</li>
 *   <li>POST /api/control/qps?value=N → 动态调整目标 QPS</li>
 * </ul>
 */
public class ControlHandler implements HttpHandler {

    /** 引擎管理服务（单例） */
    private final EngineService engineService = EngineService.getInstance();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // 处理 CORS 预检
        if (HttpUtil.handleCors(ex)) return;

        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();

        try {
            switch (path) {
                case "/api/control/start"  -> handleStart(ex, method);
                case "/api/control/stop"   -> handleStop(ex, method);
                case "/api/control/status" -> handleStatus(ex, method);
                case "/api/control/qps"    -> handleSetQps(ex, method);
                default -> HttpUtil.sendError(ex, 404, "路径不存在: " + path);
            }
        } catch (Exception e) {
            HttpUtil.sendError(ex, 500, "服务器内部错误: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // POST /api/control/start
    // --------------------------------------------------
    /**
     * 启动压测引擎。
     * <p>
     * 请求体（JSON，所有字段均可省略，省略时使用 config.toml 中的默认值）：
     * <pre>
     * {
     *   "targetIp":      "192.168.1.1",   // 目标服务器 IP
     *   "targetPort":    8080,             // 目标端口
     *   "targetQps":     50000,            // 最大 QPS（0 = 无限制）
     *   "templateName":  "Health Check",  // HTTP 模板名称
     *   "selfBenchmark": false             // 是否启用自测服务端
     * }
     * </pre>
     */
    private void handleStart(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 POST 请求");
            return;
        }
        if (engineService.isRunning()) {
            HttpUtil.sendError(ex, 409, "引擎已在运行中，请先调用 /api/control/stop");
            return;
        }

        // 解析请求体（所有参数可选）
        JsonNode body = HttpUtil.readBody(ex);
        String  targetIp      = body.has("targetIp")      ? body.get("targetIp").asText()      : null;
        int     targetPort    = body.has("targetPort")    ? body.get("targetPort").asInt()      : 0;
        long    targetQps     = body.has("targetQps")     ? body.get("targetQps").asLong()     : 0L;
        String  templateName  = body.has("templateName")  ? body.get("templateName").asText()  : null;
        boolean selfBenchmark = body.has("selfBenchmark") && body.get("selfBenchmark").asBoolean();

        try {
            engineService.start(targetIp, targetPort, targetQps, templateName, selfBenchmark);
            HttpUtil.sendJson(ex, Map.of("status", "started", "message", "压测引擎已成功启动"));
        } catch (Exception e) {
            HttpUtil.sendError(ex, 500, "启动失败: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // POST /api/control/stop
    // --------------------------------------------------
    /**
     * 停止压测引擎。
     */
    private void handleStop(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 POST 请求");
            return;
        }
        engineService.stop();
        HttpUtil.sendJson(ex, Map.of("status", "stopped", "message", "压测引擎已停止"));
    }

    // --------------------------------------------------
    // GET /api/control/status
    // --------------------------------------------------
    /**
     * 查询引擎当前运行状态和实时指标。
     * <p>
     * 响应示例：
     * <pre>
     * {
     *   "running":          true,
     *   "currentTargetQps": 25000,
     *   "totalTargetQps":   50000,
     *   "sentQps":          24500,
     *   "recvQps":          24300,
     *   "avgLatencyMs":     1.23,
     *   "totalSent":        1234567,
     *   "totalRecv":        1230000,
     *   "uptimeSeconds":    120
     * }
     * </pre>
     */
    private void handleStatus(HttpExchange ex, String method) throws IOException {
        if (!"GET".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 GET 请求");
            return;
        }
        HttpUtil.sendJson(ex, engineService.getStatus());
    }

    // --------------------------------------------------
    // POST /api/control/qps?value=N
    // --------------------------------------------------
    /**
     * 动态调整目标 QPS（引擎运行期间实时生效）。
     * <p>
     * 示例：POST /api/control/qps?value=100000
     */
    private void handleSetQps(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 POST 请求");
            return;
        }
        if (!engineService.isRunning()) {
            HttpUtil.sendError(ex, 409, "引擎未在运行，无法调整 QPS");
            return;
        }
        String valueStr = HttpUtil.getQueryParam(ex, "value");
        if (valueStr == null) {
            HttpUtil.sendError(ex, 400, "缺少查询参数: value（示例: ?value=50000）");
            return;
        }
        try {
            long qps = Long.parseLong(valueStr);
            engineService.setTargetQps(qps);
            HttpUtil.sendJson(ex, Map.of("status", "ok", "targetQps", qps));
        } catch (NumberFormatException e) {
            HttpUtil.sendError(ex, 400, "value 必须是整数: " + valueStr);
        }
    }
}
