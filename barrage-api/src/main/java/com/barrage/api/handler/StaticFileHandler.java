package com.barrage.api.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 静态文件服务处理器。
 * <p>
 * 从 {@code ./www/} 目录读取前端构建产物并返回给浏览器。
 * 支持 SPA 路由：非 {@code /api/} 前缀且文件不存在时，fallback 到 {@code index.html}。
 */
public class StaticFileHandler implements HttpHandler {

    private static final Path WWW_ROOT = Path.of("www");

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // CORS 头
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        String path = ex.getRequestURI().getPath();

        // 去掉开头的 /
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) {
            relative = "index.html";
        }

        Path file = WWW_ROOT.resolve(relative).normalize();

        // 安全检查：防止路径穿越
        if (!file.startsWith(WWW_ROOT)) {
            ex.sendResponseHeaders(403, -1);
            return;
        }

        // SPA fallback：文件不存在且不是 /api/ 请求 → index.html
        if (!Files.isRegularFile(file)) {
            file = WWW_ROOT.resolve("index.html");
        }

        if (!Files.isRegularFile(file)) {
            byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(msg);
            }
            return;
        }

        Path fileName = file.getFileName();
        String contentType = guessContentType(fileName != null ? fileName.toString() : "");
        byte[] data = Files.readAllBytes(file);

        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html"))      return "text/html; charset=UTF-8";
        if (lower.endsWith(".js"))        return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".css"))       return "text/css; charset=UTF-8";
        if (lower.endsWith(".json"))      return "application/json; charset=UTF-8";
        if (lower.endsWith(".png"))       return "image/png";
        if (lower.endsWith(".jpg")
         || lower.endsWith(".jpeg"))      return "image/jpeg";
        if (lower.endsWith(".svg"))       return "image/svg+xml";
        if (lower.endsWith(".ico"))       return "image/x-icon";
        if (lower.endsWith(".woff"))      return "font/woff";
        if (lower.endsWith(".woff2"))     return "font/woff2";
        if (lower.endsWith(".ttf"))       return "font/ttf";
        return "application/octet-stream";
    }
}
