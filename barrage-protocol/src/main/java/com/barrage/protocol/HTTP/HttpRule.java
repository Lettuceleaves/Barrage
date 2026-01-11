package com.barrage.protocol.HTTP;

import com.barrage.kernel.config.GlobalConfig;
import com.barrage.protocol.ProtocolRule;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 严格的 HTTP/1.1 协议规则实现。
 * <p>
 * 该类负责校验用户输入、同步全局配置并构建符合 RFC 9112 规范的报文。
 * </p>
 */
public class HttpRule implements ProtocolRule {
    private static final String CRLF = "\r\n";

    // 1. 标准 HTTP 方法集校验
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "TRACE"
    );

    // 2. 严格的 Host/IP 校验正则 (支持域名、IPv4 及 1-65535 端口校验)
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$"
    );

    @Override
    public List<String> requiredComponents() {
        // 定义交互式装填所需的五个核心维度
        return List.of("Method", "Host", "Port", "Path", "Body");
    }

    @Override
    public String build(Map<String, Object> components) {
        // --- 1. 数据提取与默认值注入 (关联 GlobalConfig) ---
        String rawMethod = (String) components.getOrDefault("Method", "GET");
        String rawHost = (String) components.getOrDefault("Host", GlobalConfig.getIP());
        String rawPort = (String) components.getOrDefault("Port", String.valueOf(GlobalConfig.getPORT()));
        String rawPath = (String) components.getOrDefault("Path", "/");
        String rawBody = (String) components.getOrDefault("Body", "");

        // --- 2. 严格校验与合规性处理 ---

        // A. Method 校验 (转大写)
        String method = rawMethod.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported HTTP Method: " + method);
        }

        // B. Host 校验 (自动剔除协议前缀)
        String host = rawHost.trim().toLowerCase();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("Invalid Host/IP format: " + host);
        }

        // C. Port 校验与动态配置同步
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
            if (port < 1 || port > 65535) throw new Exception();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Port: " + rawPort + " (Must be 1-65535)");
        }

        // 将校验通过的参数同步到全局配置类
        GlobalConfig.updateEndpoint(host, port);

        // D. Path 校验
        String path = rawPath.trim();
        if (!path.startsWith("/")) path = "/" + path;

        // --- 3. 按照 RFC 规范拼装零拷贝报文模板 ---
        StringBuilder sb = new StringBuilder();
        String finalHost = (port == 80 || port == 443) ? host : host + ":" + port;

        // 起始行 (Request Line)
        sb.append(method).append(" ").append(path).append(" HTTP/1.1").append(CRLF);

        // 头部字段 (Mandatory Headers)
        sb.append("Host: ").append(finalHost).append(CRLF);
        sb.append("Connection: keep-alive").append(CRLF); // 压测默认长连接
        sb.append("User-Agent: Barrage-Kernel/1.0").append(CRLF);

        // Body 处理与 Content-Length 自动计算
        if (!rawBody.isEmpty()) {
            // 使用 UTF-8 字节长度，确保多字节字符不会导致报文截断
            byte[] bodyBytes = rawBody.getBytes(StandardCharsets.UTF_8);
            sb.append("Content-Length: ").append(bodyBytes.length).append(CRLF);
            sb.append(CRLF); // Header 与 Body 之间的空行
            sb.append(rawBody);
        } else {
            sb.append(CRLF); // 无 Body 时也必须有结束空行
        }

        return sb.toString();
    }
}