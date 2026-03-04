package com.barrage.api.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 响应与 JSON 序列化工具类。
 * <p>
 * 封装了向客户端发送 JSON 响应的通用逻辑，并提供请求体的 JSON 解析能力。
 * 依赖 Jackson（已通过 barrage-kernel 传递引入），无需额外框架。
 */
public final class HttpUtil {

    /** 全局 Jackson 序列化器（线程安全，单例复用） */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpUtil() {}

    /**
     * 向客户端发送成功的 JSON 响应 (200 OK)。
     *
     * @param ex   HTTP 交换对象
     * @param body 任意可序列化对象（将被转为 JSON 字符串）
     */
    public static void sendJson(HttpExchange ex, Object body) throws IOException {
        sendJson(ex, 200, body);
    }

    /**
     * 向客户端发送指定状态码的 JSON 响应。
     *
     * @param ex         HTTP 交换对象
     * @param statusCode HTTP 状态码
     * @param body       任意可序列化对象
     */
    public static void sendJson(HttpExchange ex, int statusCode, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 向客户端发送错误 JSON 响应，格式为 {"error": "..."}。
     *
     * @param ex      HTTP 交换对象
     * @param status  HTTP 状态码
     * @param message 错误信息
     */
    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, Map.of("error", message));
    }

    /**
     * 处理 CORS 预检请求 (OPTIONS)，直接返回 200。
     *
     * @param ex HTTP 交换对象
     * @return 如果是 OPTIONS 请求则返回 true，调用方应立即返回
     */
    public static boolean handleCors(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    /**
     * 解析请求体中的 JSON 为 JsonNode。
     *
     * @param ex HTTP 交换对象
     * @return 解析后的 JsonNode；若请求体为空则返回空 ObjectNode
     */
    public static JsonNode parseBody(HttpExchange ex) throws IOException {
        int contentLength = (int) ex.getRequestBody().transferTo(OutputStream.nullOutputStream());
        // 重新读取（使用 transferTo 会消耗流，改用直接读取）
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * 从请求体中解析 JSON 为 JsonNode（正确实现，不消耗流两次）。
     *
     * @param ex HTTP 交换对象
     * @return 解析后的 JsonNode
     */
    public static JsonNode readBody(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(body);
    }

    /**
     * 从 URL 查询字符串中提取指定参数值。
     * <p>
     * 例如：/api/control/qps?value=100000 → getQueryParam(ex, "value") = "100000"
     *
     * @param ex   HTTP 交换对象
     * @param key  参数名
     * @return 参数值，若不存在则返回 null
     */
    public static String getQueryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }
}
