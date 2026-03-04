package com.barrage.api.handler;

import com.barrage.api.util.HttpUtil;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 HTTP 处理器。
 * <p>
 * 挂载路径：/api/config/*
 * <ul>
 *   <li>GET  /api/config            → 读取当前 BasicConfig（来自 config.toml）</li>
 *   <li>POST /api/config            → 更新并保存 BasicConfig 到 config.toml</li>
 *   <li>GET  /api/config/templates  → 列出所有 HTTP 模板名称</li>
 *   <li>GET  /api/config/templates/{name} → 获取指定模板详情</li>
 *   <li>POST /api/config/templates  → 新增或更新模板（写入 http.toml）</li>
 * </ul>
 */
public class ConfigHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // 处理 CORS 预检
        if (HttpUtil.handleCors(ex)) return;

        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/api/config")) {
                // 根路径：读取 / 更新 BasicConfig
                if ("GET".equals(method)) {
                    handleGetConfig(ex);
                } else if ("POST".equals(method)) {
                    handleUpdateConfig(ex);
                } else {
                    HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
                }
            } else if (path.equals("/api/config/templates")) {
                // 模板列表 / 新增模板
                if ("GET".equals(method)) {
                    handleListTemplates(ex);
                } else if ("POST".equals(method)) {
                    handleSaveTemplate(ex);
                } else {
                    HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
                }
            } else if (path.equals("/api/config/templates/batch")) {
                // 批量保存所有模板（刷新 http.toml）
                if ("POST".equals(method)) {
                    handleSaveAllTemplates(ex);
                } else {
                    HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
                }
            } else if (path.startsWith("/api/config/templates/")) {
                // 指定模板详情
                String name = path.substring("/api/config/templates/".length());
                if ("GET".equals(method)) {
                    handleGetTemplate(ex, name);
                } else {
                    HttpUtil.sendError(ex, 405, "不支持的请求方法: " + method);
                }
            } else {
                HttpUtil.sendError(ex, 404, "路径不存在: " + path);
            }
        } catch (Exception e) {
            HttpUtil.sendError(ex, 500, "服务器内部错误: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // GET /api/config
    // --------------------------------------------------
    /** 返回当前 BasicConfig 中所有配置项 */
    private void handleGetConfig(HttpExchange ex) throws IOException {
        Map<String, Object> cfg = new LinkedHashMap<>();

        // [network] 网络配置
        cfg.put("ip",             BasicConfig.getIP());
        cfg.put("port",           BasicConfig.getPORT());

        // [engine] 引擎配置
        cfg.put("serverThreads",  BasicConfig.getSERVER_THREADS());
        cfg.put("clientThreads",  BasicConfig.getCLIENT_THREADS());
        cfg.put("connsPerClient", BasicConfig.getCONNS_PER_CLIENT());
        cfg.put("step",           BasicConfig.getSTEP());

        // [performance] 性能配置
        cfg.put("inFlight",       BasicConfig.getIN_FLIGHT());
        cfg.put("queueDepth",     BasicConfig.getQUEUE_DEPTH());
        cfg.put("batchSize",      BasicConfig.getBATCH_SIZE());
        cfg.put("readSz",         BasicConfig.getREAD_SZ());

        // [template] 活跃模板
        cfg.put("activeTemplateName", BasicConfig.getACTIVE_TEMPLATE_NAME());

        HttpUtil.sendJson(ex, cfg);
    }

    // --------------------------------------------------
    // POST /api/config
    // --------------------------------------------------
    /**
     * 局部更新 BasicConfig（只更新请求体中存在的字段），
     * 更新后立即持久化到 config.toml。
     */
    private void handleUpdateConfig(HttpExchange ex) throws IOException {
        JsonNode body = HttpUtil.readBody(ex);

        if (body.has("ip"))             BasicConfig.setIP(body.get("ip").asText());
        if (body.has("port"))           BasicConfig.setPORT(body.get("port").asInt());
        if (body.has("serverThreads"))  BasicConfig.setSERVER_THREADS(body.get("serverThreads").asInt());
        if (body.has("clientThreads"))  BasicConfig.setCLIENT_THREADS(body.get("clientThreads").asInt());
        if (body.has("connsPerClient")) BasicConfig.setCONNS_PER_CLIENT(body.get("connsPerClient").asInt());
        if (body.has("step"))           BasicConfig.setSTEP(body.get("step").asInt());
        if (body.has("inFlight"))       BasicConfig.setIN_FLIGHT(body.get("inFlight").asInt());
        if (body.has("queueDepth"))     BasicConfig.setQUEUE_DEPTH(body.get("queueDepth").asInt());
        if (body.has("batchSize"))      BasicConfig.setBATCH_SIZE(body.get("batchSize").asInt());
        if (body.has("readSz"))         BasicConfig.setREAD_SZ(body.get("readSz").asInt());
        if (body.has("activeTemplateName"))
            BasicConfig.setACTIVE_TEMPLATE_NAME(body.get("activeTemplateName").asText());

        // 持久化到磁盘
        BasicConfig.save();

        // 返回最新完整配置
        handleGetConfig(ex);
    }

    // --------------------------------------------------
    // GET /api/config/templates
    // --------------------------------------------------
    /** 返回所有模板的名称列表 */
    private void handleListTemplates(HttpExchange ex) throws IOException {
        List<String> names = TemplateConfig.getList();
        HttpUtil.sendJson(ex, names);
    }

    // --------------------------------------------------
    // GET /api/config/templates/{name}
    // --------------------------------------------------
    /** 返回指定名称模板的详细信息 */
    private void handleGetTemplate(HttpExchange ex, String name) throws IOException {
        List<String> details = TemplateConfig.get(name);
        if (details == null) {
            HttpUtil.sendError(ex, 404, "模板不存在: " + name);
            return;
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("name",    name);
        result.put("method",  details.get(0));
        result.put("path",    details.get(1));
        result.put("body",    details.get(2));
        result.put("headers", details.get(3));
        HttpUtil.sendJson(ex, result);
    }

    // --------------------------------------------------
    // POST /api/config/templates
    // --------------------------------------------------
    /**
     * 新增或更新模板（Upsert 语义），写入 http.toml。
     * 请求体格式（JSON）：
     * <pre>
     * {
     *   "name":    "Health Check",
     *   "method":  "GET",
     *   "path":    "/health",
     *   "body":    "",
     *   "headers": "User-Agent:Barrage"
     * }
     * </pre>
     */
    private void handleSaveTemplate(HttpExchange ex) throws IOException {
        JsonNode body = HttpUtil.readBody(ex);

        if (!body.has("name") || body.get("name").asText().isBlank()) {
            HttpUtil.sendError(ex, 400, "模板名称（name）不能为空");
            return;
        }

        String name    = body.get("name").asText();
        String method  = body.has("method")  ? body.get("method").asText()  : "GET";
        String path    = body.has("path")    ? body.get("path").asText()    : "/";
        String payload = body.has("body")    ? body.get("body").asText()    : "";
        String headers = body.has("headers") ? body.get("headers").asText() : "";

        TemplateConfig.save(name, method, path, payload, headers);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("name",    name);
        result.put("method",  method);
        result.put("path",    path);
        result.put("body",    payload);
        result.put("headers", headers);
        HttpUtil.sendJson(ex, result);
    }

    // --------------------------------------------------
    // POST /api/config/templates/batch
    // --------------------------------------------------
    /**
     * 批量保存（替换）所有 HTTP 请求模板，刷新 http.toml。
     * 请求体为 JSON 数组：
     * <pre>
     * [
     *   { "name": "Login", "method": "POST", "path": "/login", "body": "{}", "headers": "" },
     *   ...
     * ]
     * </pre>
     */
    private void handleSaveAllTemplates(HttpExchange ex) throws IOException {
        JsonNode body = HttpUtil.readBody(ex);
        if (!body.isArray()) {
            HttpUtil.sendError(ex, 400, "请求体必须是 JSON 数组");
            return;
        }

        List<Map<String, String>> list = new java.util.ArrayList<>();
        for (JsonNode item : body) {
            Map<String, String> t = new HashMap<>();
            t.put("name",    item.has("name")    ? item.get("name").asText()    : "");
            t.put("method",  item.has("method")  ? item.get("method").asText()  : "GET");
            t.put("path",    item.has("path")    ? item.get("path").asText()    : "/");
            t.put("body",    item.has("body")    ? item.get("body").asText()    : "");
            t.put("headers", item.has("headers") ? item.get("headers").asText() : "");
            list.add(t);
        }

        TemplateConfig.saveAll(list);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("count",  list.size());
        HttpUtil.sendJson(ex, result);
    }
}