package com.barrage.protocol.HTTP;

import com.barrage.kernel.config.basic.BasicConfig;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP 协议模版（双模式支持）。
 * <p>
 * 模式 A (Raw): 直接存储字节数组（来自文件），toBytes() 直接返回数据。
 * 模式 B (Field): 存储字段（来自控制台），toBytes() 动态拼装报文。
 */
public class HttpTemplate {

    // --- 模式 A: 原生字节支持 ---
    private byte[] rawBytes;

    /**
     * 构造函数 (Raw Mode)：用于 FileDataSource
     */
    public HttpTemplate(byte[] rawBytes) {
        this.rawBytes = rawBytes;
    }

    // --- 模式 B: 动态字段支持 ---
    public HttpTemplate() {
        // 无参构造，用于 ConsoleDataSource
    }

    private static final String CRLF = "\r\n";
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern HOST_PATTERN = Pattern.compile("^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$");

    private String method = "GET";
    private String path = "/";
    private String body = "";
    private String headers = ""; // 额外的 Header
    private String host = BasicConfig.getIP();
    private int port = BasicConfig.getPORT();

    /**
     * 【核心方法】获取最终用于发送的字节数组。
     */
    public byte[] toBytes() {
        // 1. 如果是 Raw 模式（文件源），直接返回原生数据
        if (rawBytes != null) {
            return rawBytes;
        }
        // 2. 否则根据字段动态生成
        return generate();
    }

    /**
     * 根据字段组装 HTTP 报文。
     */
    private byte[] generate() {
        StringBuilder sb = new StringBuilder();

        // 拼接 Request Line
        sb.append(method).append(" ").append(path).append(" HTTP/1.1").append(CRLF);

        // 拼接核心 Header
        String hostVal = (port == 80 || port == 443) ? host : host + ":" + port;
        sb.append("Host: ").append(hostVal).append(CRLF);
        sb.append("Connection: keep-alive").append(CRLF);
        sb.append("User-Agent: Barrage-Engine/1.0").append(CRLF);

        // 拼接额外 Header
        if (headers != null && !headers.isEmpty()) {
            sb.append(headers);
            if (!headers.endsWith("\n")) sb.append(CRLF);
        }

        // 处理 Body 和 Content-Length
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (bodyBytes.length > 0) {
            sb.append("Content-Length: ").append(bodyBytes.length).append(CRLF);
        }

        sb.append(CRLF); // Header 结束空行

        // 将 Header 转为字节
        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        // 合并 Header + Body
        if (bodyBytes.length == 0) {
            return headerBytes;
        } else {
            byte[] packet = new byte[headerBytes.length + bodyBytes.length];
            System.arraycopy(headerBytes, 0, packet, 0, headerBytes.length);
            System.arraycopy(bodyBytes, 0, packet, headerBytes.length, bodyBytes.length);
            return packet;
        }
    }

    /*
      校验字段并同步全局配置 (仅用于 Console 模式)
     */
    /**
     * 校验字段并同步全局配置 (仅用于 Console 模式)
     * <p>
     * 作用：
     * 1. 确保 Method, Host, Port 符合 HTTP/TCP 规范。
     * 2. 自动修正 Path 格式。
     * 3. 将验证通过的目标地址更新到 {@link BasicConfig}，供 Engine 使用。
     */
    public void check() {
        // 1. Raw 模式（文件源）默认信任，跳过校验
        if (rawBytes != null) return;

        // 2. 校验 Method
        if (method == null || method.trim().isEmpty()) {
            method = "GET"; // 默认兜底
        }
        this.method = method.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(this.method)) {
            throw new IllegalArgumentException("Unsupported HTTP Method: " + this.method);
        }

        // 3. 校验 Host
        // 逻辑：如果 Host 既不匹配标准域名/IP正则，也不是 "localhost"，则抛出异常
        if (host == null || (!HOST_PATTERN.matcher(host).matches() && !"localhost".equalsIgnoreCase(host))) {
            throw new IllegalArgumentException("Invalid Host format: " + host);
        }

        // 4. 校验 Port (补充：TCP 端口必须在 1-65535 之间)
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Port: " + port + " (Must be 1-65535)");
        }

        // 5. 校验 Path (补充：确保以 "/" 开头)
        if (path == null || path.trim().isEmpty()) {
            this.path = "/";
        } else {
            this.path = path.trim();
            if (!this.path.startsWith("/")) {
                this.path = "/" + this.path;
            }
        }

        // 6. 同步配置 (关键步骤：确保 ClientEngine 能连上正确的目标)
        BasicConfig.updateEndpoint(host, port);
    }

    // Setters
    public void setMethod(String method) { this.method = method; }
    public void setPath(String path) { this.path = path; }
    public void setBody(String body) { this.body = body; }
    public void setHeaders(String headers) { this.headers = headers; }
    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
}