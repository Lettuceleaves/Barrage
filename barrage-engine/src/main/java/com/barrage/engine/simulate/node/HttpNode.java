package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;
import com.barrage.engine.simulate.pool.NetworkInfrastructure;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpResponseView;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 请求节点 (Http Action Node).
 * <p>
 * 仿真引擎中最核心的动作节点，负责发起真实的网络请求。
 * <p>
 * <b>执行流程：</b>
 * <ol>
 * <li><b>请求构建：</b> 调用模板引擎生成请求报文 (如果尚未生成)，并写入用户的 Request Buffer。</li>
 * <li><b>请求发送：</b> 将 Request Buffer 指针传递给底层 IO 线程
 * ({@link NetworkInfrastructure})。</li>
 * <li><b>异步等待：</b> 挂起虚拟线程，等待 IO 完成。</li>
 * <li><b>响应处理：</b> IO 完成后被唤醒，解析响应状态码，更新 Context 状态。</li>
 * </ol>
 */
public class HttpNode extends GraphNode {

    private final String templateRef;
    private static final ThreadLocal<HttpResponseView> VIEW_HOLDER = ThreadLocal.withInitial(HttpResponseView::new);

    public HttpNode(String name, List<TransitionContext> transitionContexts, String templateRef) {
        super(name, "HTTP", transitionContexts);
        this.templateRef = templateRef;
        if (transitionContexts == null || transitionContexts.isEmpty()) {
            throw new IllegalArgumentException("HttpNode must have at least 1 transition.");
        }
    }

    @Override
    public void run(SimulationContext context) {
        NetworkInfrastructure infra = context.getNetworkInfrastructure();

        // =================================================================
        // 1. 模板渲染 (Request Generation)
        // =================================================================
        // 每次执行都重新渲染，确保不同 HttpNode 使用各自的模板数据
        context.setDataReference(0, 0);
        if (!renderRequestFromTemplate(context)) {
            handleFailure(context, 500);
            return;
        }

        // 获取准备好的请求数据
        MemorySegment requestData = context.getDataAsSegment();

        // =================================================================
        // 2. 提交请求 (Submit)
        // =================================================================
        try {
            infra.submitRequest(
                    context.getUserId(),
                    requestData,
                    context.getSlotMetadataSegment()).get(); // <--- 挂起等待

            // =================================================================
            // 3. 处理响应 (Zero-Copy View)
            // =================================================================
            long actualLen = context.getDataLength();
            if (actualLen <= 0) {
                handleFailure(context, 503);
                return;
            }

            MemorySegment responseBufferView = context.getResponseBuffer();
            if (responseBufferView == null || responseBufferView.equals(MemorySegment.NULL)) {
                handleFailure(context, 500);
                return;
            }

            HttpResponseView view = VIEW_HOLDER.get();
            view.wrap(responseBufferView, (int) actualLen);

            int statusCode = view.getStatusCode();
            context.setStatus(statusCode);

            // 简单的路由逻辑
            if (statusCode >= 200 && statusCode < 400) {
                context.setNextTransitionIndex(0);
            } else {
                int failIndex = this.transitionContexts.size() > 1 ? 1 : 0;
                context.setNextTransitionIndex(failIndex);
            }

        } catch (Exception e) {
            handleFailure(context, 504);
        }
    }

    /**
     * 根据 templateRef 渲染 HTTP 请求并写入用户私有 Request Buffer。
     */
    private boolean renderRequestFromTemplate(SimulationContext context) {
        String method = "GET";
        String path = "/";
        String body = "";
        String headers = "";

        if (templateRef != null && !templateRef.isBlank()) {
            List<String> detail = TemplateConfig.get(templateRef);
            if (detail != null && detail.size() >= 4) {
                method = safe(detail.get(0), "GET").toUpperCase();
                path = normalizePath(detail.get(1));
                body = safe(detail.get(2), "");
                headers = safe(detail.get(3), "");
            }
        }

        String targetHost = BasicConfig.getIP() + ":" + BasicConfig.getPORT();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(targetHost).append("\r\n");
        sb.append("User-Agent: Barrage-Agent/1.0\r\n");
        appendExtraHeaders(sb, headers);
        if (bodyBytes.length > 0) {
            sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");

        byte[] headBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        int totalLen = headBytes.length + bodyBytes.length;

        MemorySegment reqBuf = context.getRequestBuffer();
        if (totalLen > reqBuf.byteSize()) {
            System.err.printf("[HttpNode] Request exceeds buffer (%d > %d), template=%s%n",
                    totalLen, reqBuf.byteSize(), templateRef);
            return false;
        }

        MemorySegment.copy(MemorySegment.ofArray(headBytes), 0, reqBuf, 0, headBytes.length);
        if (bodyBytes.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(bodyBytes), 0, reqBuf, headBytes.length, bodyBytes.length);
        }
        context.setDataReference(reqBuf.address(), totalLen);
        return true;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "/";
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    private void appendExtraHeaders(StringBuilder sb, String rawHeaders) {
        if (rawHeaders == null || rawHeaders.isBlank()) return;
        String[] pairs = rawHeaders.split(",");
        for (String p : pairs) {
            String line = p.trim();
            if (line.isBlank()) continue;
            int idx = line.indexOf(':');
            if (idx <= 0 || idx == line.length() - 1) continue;
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            if (key.isEmpty() || val.isEmpty()) continue;
            // Host/Content-Length/Connection 由引擎统一管理，避免冲突
            if ("host".equalsIgnoreCase(key) ||
                "content-length".equalsIgnoreCase(key) ||
                "connection".equalsIgnoreCase(key)) {
                continue;
            }
            sb.append(key).append(": ").append(val).append("\r\n");
        }
    }

    private void handleFailure(SimulationContext context, int errorCode) {
        context.setStatus(errorCode);
        int failIndex = this.transitionContexts.size() > 1 ? 1 : 0;
        context.setNextTransitionIndex(failIndex);
    }

    public String getTemplateRef() {
        return templateRef;
    }

    @Override
    protected void printExecutionDetails(SimulationContext context) {
        System.out.printf("    [Debug] IO Result: Status=%d, Len=%d%n",
                context.getStatus(), context.getDataLength());
    }
}
