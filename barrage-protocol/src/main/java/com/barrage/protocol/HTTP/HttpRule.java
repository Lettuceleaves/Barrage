package com.barrage.protocol.HTTP;

import com.barrage.kernel.config.GlobalConfig;
import com.barrage.protocol.ProtocolRule;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP/1.1 协议构建规则的具体实现。
 * <p>
 * 该类负责将用户输入的松散参数（Method, Host, Path 等）转换为符合 RFC 9112 标准的
 * 严格 HTTP 报文格式。它不仅充当协议格式化器，还兼任输入校验器。
 *
 * <h2>核心职责：</h2>
 * <ul>
 * <li><b>合规性校验：</b> 强制检查 HTTP 方法合法性、端口范围及 Host 格式。</li>
 * <li><b>状态同步：</b> 构建成功后，自动将目标 Host 和 Port 同步至 {@link GlobalConfig}，
 * 确保底层的 {@code ClientEngine} 能够连接到正确的目标。</li>
 * <li><b>报文组装：</b> 自动计算 {@code Content-Length}，强制开启 {@code Connection: keep-alive}，
 * 并处理 CRLF (\r\n) 分隔符。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see ProtocolRule
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

    /**
     * 获取构建 HTTP 报文所需的必要组件列表。
     * <p>
     * 这些组件名称将作为 {@code ConsoleDataSource} 的交互提示符。
     *
     * @return 包含 "Method", "Host", "Port", "Path", "Body" 的列表
     */
    @Override
    public List<String> requiredComponents() {
        // 定义交互式装填所需的五个核心维度
        return List.of("Method", "Host", "Port", "Path", "Body");
    }

    /**
     * 执行构建逻辑。
     * <p>
     * 该方法包含三个阶段：
     * <ol>
     * <li><b>数据清洗：</b> 读取 Map 输入，若缺失则回退到 {@link GlobalConfig} 的默认值。</li>
     * <li><b>严格校验：</b> 验证格式并更新 {@link GlobalConfig} 的目标端点。</li>
     * <li><b>协议序列化：</b> 使用 {@link StringBuilder} 拼接最终的 HTTP 报文。</li>
     * </ol>
     *
     * @param components 包含用户输入值的键值对映射
     * @return 符合 HTTP/1.1 格式的请求字符串（包含 Header 和 Body）
     * @throws IllegalArgumentException 如果 Method 非法、端口越界或 Host 格式错误
     */
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

        // 关键副作用：将校验通过的参数同步到全局配置类
        // 这确保了 ClientEngine 能够连接到用户刚刚输入的地址
        GlobalConfig.updateEndpoint(host, port);

        // D. Path 校验
        String path = rawPath.trim();
        if (!path.startsWith("/")) path = "/" + path;

        // --- 3. 按照 RFC 规范拼装零拷贝报文模板 ---
        StringBuilder sb = new StringBuilder();
        // 只有非标准端口才需要在 Host 头中显式携带端口号
        String finalHost = (port == 80 || port == 443) ? host : host + ":" + port;

        // 起始行 (Request Line)
        sb.append(method).append(" ").append(path).append(" HTTP/1.1").append(CRLF);

        // 头部字段 (Mandatory Headers)
        sb.append("Host: ").append(finalHost).append(CRLF);
        sb.append("Connection: keep-alive").append(CRLF); // 压测默认强制长连接
        sb.append("User-Agent: Barrage-Kernel/1.0").append(CRLF);

        // Body 处理与 Content-Length 自动计算
        if (!rawBody.isEmpty()) {
            // 使用 UTF-8 字节长度，确保多字节字符（如中文）不会导致报文截断或解析错误
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