package com.barrage.api.handler;

import com.barrage.api.service.SimulateService;
import com.barrage.api.util.HttpUtil;
import com.barrage.engine.simulate.config.GraphLoader.BranchDTO;
import com.barrage.engine.simulate.config.GraphLoader.NodeDTO;
import com.barrage.engine.simulate.config.GraphLoader.ScenarioConfigDTO;
import com.barrage.engine.simulate.config.GraphLoader.TransitionDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模拟测试 HTTP 处理器。
 * <p>
 * 挂载路径：/api/simulate/*
 * <ul>
 *   <li>GET  /api/simulate/scenarios               → 列出所有 YAML 场景文件</li>
 *   <li>GET  /api/simulate/scenarios/{file}        → 获取指定场景文件内容</li>
 *   <li>POST /api/simulate/scenarios               → 新建或覆盖场景文件</li>
 *   <li>POST /api/simulate/start?scenario={file}   → 启动模拟测试（指定场景）</li>
 *   <li>POST /api/simulate/stop                    → 停止模拟测试</li>
 *   <li>GET  /api/simulate/status                  → 查询模拟测试运行状态</li>
 * </ul>
 */
public class SimulateHandler implements HttpHandler {

    private final SimulateService srv = SimulateService.getInstance();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (HttpUtil.handleCors(ex)) return;

        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/api/simulate/scenarios")) {
                handleScenarios(ex, method);
            } else if (path.startsWith("/api/simulate/scenarios/")) {
                String file = path.substring("/api/simulate/scenarios/".length());
                handleGetScenario(ex, method, file);
            } else if (path.equals("/api/simulate/start")) {
                handleStart(ex, method);
            } else if (path.equals("/api/simulate/stop")) {
                handleStop(ex, method);
            } else if (path.equals("/api/simulate/status")) {
                handleStatus(ex, method);
            } else {
                HttpUtil.sendError(ex, 404, "路径不存在: " + path);
            }
        } catch (Exception e) {
            HttpUtil.sendError(ex, 500, "服务器内部错误: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // GET/POST /api/simulate/scenarios
    // --------------------------------------------------

    private void handleScenarios(HttpExchange ex, String method) throws IOException {
        if ("GET".equals(method)) {
            // 列出所有 YAML 场景文件名
            HttpUtil.sendJson(ex, srv.listScenarios());
        } else if ("POST".equals(method)) {
            // 新建或覆盖场景文件
            handleSaveScenario(ex);
        } else {
            HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
        }
    }

    // --------------------------------------------------
    // GET /api/simulate/scenarios/{file}
    // --------------------------------------------------

    /**
     * 获取指定场景文件的详细内容（以 JSON 形式返回 DTO）。
     */
    private void handleGetScenario(HttpExchange ex, String method, String fileName) throws IOException {
        if (!"GET".equals(method)) {
            HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
            return;
        }
        try {
            ScenarioConfigDTO dto = srv.loadScenario(fileName);
            HttpUtil.sendJson(ex, dto);
        } catch (RuntimeException e) {
            HttpUtil.sendError(ex, 404, e.getMessage());
        }
    }

    // --------------------------------------------------
    // POST /api/simulate/scenarios（body 为完整场景 JSON）
    // --------------------------------------------------

    /**
     * 解析请求体 JSON 并保存为场景文件。
     * <p>
     * 请求体格式（JSON）：
     * <pre>
     * {
     *   "fileName":    "my_scene.yaml",      // 保存的文件名（必填）
     *   "graphName":   "My Test Flow",
     *   "startNodeId": "node_start",
     *   "nodes": [
     *     {
     *       "id": "node_start", "type": "START", "name": "Start",
     *       "transition": { "mode": "NO_DELAY", "next": "node_req" }
     *     },
     *     {
     *       "id": "node_req", "type": "HTTP", "name": "Login",
     *       "templateRef": "tpl_login_001",
     *       "transition": { "mode": "NO_DELAY", "next": "node_end" }
     *     },
     *     {
     *       "id": "node_end", "type": "TERMINAL", "name": "Done",
     *       "resultTag": "SUCCESS", "saveContext": false
     *     }
     *   ]
     * }
     * </pre>
     */
    private void handleSaveScenario(HttpExchange ex) throws IOException {
        JsonNode body = HttpUtil.readBody(ex);

        if (!body.has("fileName") || body.get("fileName").asText().isBlank()) {
            HttpUtil.sendError(ex, 400, "缺少必填字段: fileName");
            return;
        }

        String fileName = body.get("fileName").asText();

        // 将 JsonNode 反序列化为 DTO
        ScenarioConfigDTO dto = parseScenarioDto(body);

        try {
            srv.saveScenario(fileName, dto);
            HttpUtil.sendJson(ex, Map.of(
                    "status",   "saved",
                    "fileName", fileName.endsWith(".yaml") || fileName.endsWith(".yml")
                                ? fileName : fileName + ".yaml"
            ));
        } catch (IOException e) {
            HttpUtil.sendError(ex, 500, "场景文件写入失败: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // POST /api/simulate/start?scenario={file}
    // --------------------------------------------------

    /**
     * 启动模拟测试。
     * <p>
     * 通过查询参数 {@code scenario} 指定使用的场景文件名；
     * 若不指定则使用默认的 {@code scenario.yaml}。
     * <p>
     * 示例：POST /api/simulate/start?scenario=my_scene.yaml
     */
    private void handleStart(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 POST 请求");
            return;
        }
        if (srv.isRunning()) {
            HttpUtil.sendError(ex, 409, "模拟测试已在运行，请先调用 /api/simulate/stop");
            return;
        }

        String scenario = HttpUtil.getQueryParam(ex, "scenario");

        try {
            srv.start(scenario);
            HttpUtil.sendJson(ex, Map.of(
                    "status",   "started",
                    "scenario", scenario != null ? scenario : "scenario.yaml",
                    "message",  "模拟测试已成功启动"
            ));
        } catch (RuntimeException e) {
            HttpUtil.sendError(ex, 500, "启动失败: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // POST /api/simulate/stop
    // --------------------------------------------------

    private void handleStop(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 POST 请求");
            return;
        }
        srv.stop();
        HttpUtil.sendJson(ex, Map.of("status", "stopped", "message", "模拟测试已停止"));
    }

    // --------------------------------------------------
    // GET /api/simulate/status
    // --------------------------------------------------

    /**
     * 查询模拟测试当前状态。
     * <p>
     * 响应示例（运行中）：
     * <pre>
     * {
     *   "running":       true,
     *   "scenario":      "scenario.yaml",
     *   "activeAgents":  850000,
     *   "uptimeSeconds": 30
     * }
     * </pre>
     */
    private void handleStatus(HttpExchange ex, String method) throws IOException {
        if (!"GET".equals(method)) {
            HttpUtil.sendError(ex, 405, "请使用 GET 请求");
            return;
        }
        HttpUtil.sendJson(ex, srv.getStatus());
    }

    // --------------------------------------------------
    // 私有工具：JsonNode → ScenarioConfigDTO
    // --------------------------------------------------

    /**
     * 将请求体 JsonNode 解析为 {@link ScenarioConfigDTO}。
     * 直接使用 Jackson 进行结构映射，无需手动遍历字段。
     */
    private ScenarioConfigDTO parseScenarioDto(JsonNode body) {
        try {
            // 重用 HttpUtil 的全局 ObjectMapper 将 JsonNode → DTO
            return HttpUtil.MAPPER.treeToValue(body, ScenarioConfigDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("场景配置 JSON 格式错误: " + e.getMessage(), e);
        }
    }
}
