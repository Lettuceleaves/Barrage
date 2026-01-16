package com.barrage.protocol.HTTP;

import com.barrage.kernel.config.basic.BasicConfig;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP 协议报文模版 (Dual-Mode Request Abstraction)。
 * <p>
 * 该类是 Barrage 协议层的核心数据结构，用于封装即将发送给目标的 HTTP 请求数据。
 * 为了兼容不同的数据源策略（文件 vs 控制台），它内部实现了两种互斥的数据持有模式，
 * 对上层引擎屏蔽了底层的报文构造细节。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>双模式支持 (Dual-Mode)：</b>
 * <ul>
 * <li><b>Raw Mode (原生模式):</b> 直接持有预先读取的二进制字节数组 ({@code rawBytes})。
 * 适用于 {@code FileDataSource}，支持零解析直接发送，性能最高。</li>
 * <li><b>Builder Mode (构造模式):</b> 持有结构化的 HTTP 字段 (Method, Host, Body 等)。
 * 适用于 {@code ConsoleDataSource}，在发送前通过 {@code generate()} 动态拼装符合 RFC 标准的报文。</li>
 * </ul>
 * </li>
 * <li><b>自动协议封装：</b> 在构造模式下，自动计算 {@code Content-Length}，
 * 强制添加 {@code Connection: keep-alive} 和 {@code Host} 头，确保长连接复用。</li>
 * <li><b>配置同步机制：</b> {@code check()} 方法不仅负责校验字段合法性，
 * 还会将最终确认的 Host 和 Port 同步回 {@link BasicConfig}，确保网络层建立连接的目标地址正确。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 该类包含可变的成员字段。通常在主线程完成构建和校验后，会将其转为不可变的字节数组或
 * 被复制到堆外内存中供 Worker 线程读取，因此在初始化阶段后不应跨线程修改。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class HttpTemplate {

    // --- 模式 A: 原生字节支持 ---
    private byte[] rawBytes;

    /**
     * 构造函数 (Raw Mode)。
     * <p>
     * 初始化为原生字节模式，通常用于直接加载文件内容。
     * 在此模式下，{@link #toBytes()} 将直接返回传入的数组，跳过所有组装逻辑。
     *
     * @param rawBytes 完整的 HTTP 请求报文（包含 Header 和 Body）的字节数组
     */
    public HttpTemplate(byte[] rawBytes) {
        this.rawBytes = rawBytes;
    }

    // --- 模式 B: 动态字段支持 ---

    /**
     * 构造函数 (Builder Mode)。
     * <p>
     * 初始化为空模版，等待通过 Setter 方法填充字段。
     * 通常用于控制台交互式构建。
     */
    public HttpTemplate() {
        // 无参构造，用于 ConsoleDataSource
    }

    private static final String CRLF = "\r\n";
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");
    /**
     * 域名/IP 正则校验器。
     * 用于确保用户输入的 Host 符合 DNS 标准或 IPv4 格式。
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$");

    private String method = "GET";
    private String path = "/";
    private String body = "";
    private String headers = ""; // 额外的 Header
    private String host = BasicConfig.getIP();
    private int port = BasicConfig.getPORT();

    /**
     * 获取最终用于发送的二进制报文数据。
     * <p>
     * 这是一个策略路由方法：
     * <ul>
     * <li>如果处于 <b>Raw Mode</b>，直接返回原生字节数组（零拷贝开销）。</li>
     * <li>如果处于 <b>Builder Mode</b>，调用 {@link #generate()} 动态组装报文。</li>
     * </ul>
     *
     * @return 准备好写入 Socket 的 HTTP 请求字节数组
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
     * 动态生成 HTTP 报文 (Builder Mode 核心逻辑)。
     * <p>
     * 根据当前持有的字段，拼接符合 HTTP/1.1 标准的请求报文。
     * <p>
     * <b>组装规则：</b>
     * <ul>
     * <li><b>Request Line:</b> {@code METHOD PATH HTTP/1.1}</li>
     * <li><b>Host:</b> 如果端口是 80/443 则省略端口号，否则拼接 {@code host:port}。</li>
     * <li><b>Headers:</b> 强制注入 {@code Connection: keep-alive} 和 {@code User-Agent}。</li>
     * <li><b>Body:</b> 将 Body 字符串转为 UTF-8 字节，并自动计算 {@code Content-Length}。</li>
     * </ul>
     *
     * @return 组装后的字节数组
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

    /**
     * 执行严格的参数校验并同步全局配置 (Side-Effect)。
     * <p>
     * 该方法主要用于 <b>Console Mode</b> 下的用户输入验证。
     * <p>
     * <b>校验逻辑：</b>
     * <ol>
     * <li><b>Method:</b> 必须在允许的 HTTP 方法白名单内 ({@code ALLOWED_METHODS})。</li>
     * <li><b>Host:</b> 必须匹配域名正则或为 "localhost"。</li>
     * <li><b>Port:</b> 必须在 1-65535 范围内。</li>
     * <li><b>Path:</b> 自动修剪空格并确保以 "/" 开头。</li>
     * </ol>
     * <p>
     * <b>副作用：</b>
     * 校验通过后，会调用 {@link BasicConfig#updateEndpoint(String, int)} 更新全局配置。
     * 这一步至关重要，因为 {@code ClientEngine} 启动时是从全局配置中读取连接目标的。
     *
     * @throws IllegalArgumentException 如果任何参数格式不正确
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