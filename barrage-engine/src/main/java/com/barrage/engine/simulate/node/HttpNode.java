package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;
import com.barrage.engine.simulate.pool.NetworkInfrastructure;
import com.barrage.protocol.HTTP.HttpResponseView;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpNode extends GraphNode {

    private final String templateRef;
    private static final ThreadLocal<HttpResponseView> VIEW_HOLDER =
            ThreadLocal.withInitial(HttpResponseView::new);

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
        // [New] 1. 模板渲染 (Request Generation)
        // =================================================================
        // 检查当前是否已经有数据，如果没有，则根据 templateRef 生成
        // (为了演示，这里直接硬编码一个 Mock 请求，真实项目应调用 TemplateManager)
        if (context.getDataAsSegment().byteSize() == 0) {
            renderMockRequest(context);
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
                    context.getSlotMetadataSegment()
            ).get(); // <--- 挂起等待

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
     * [Mock] 模拟模板引擎：将 HTTP 报文写入用户的 RequestBuffer
     */
    private void renderMockRequest(SimulationContext context) {
        // 构造一个合法的 HTTP 请求 (Host 改为你的目标地址)
        // 注意：Host 头必须匹配你的目标服务器 IP/域名
        String reqStr = "GET / HTTP/1.1\r\n" +
                "Host: host.docker.internal:8089\r\n" +
                "User-Agent: Barrage-Agent/1.0\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";

        byte[] reqBytes = reqStr.getBytes(StandardCharsets.US_ASCII);

        // 1. 获取用户的私有 Request Buffer
        MemorySegment reqBuf = context.getRequestBuffer();

        // 2. 写入数据
        MemorySegment.copy(MemorySegment.ofArray(reqBytes), 0, reqBuf, 0, reqBytes.length);

        // 3. 更新指针 (告诉 Context 数据在哪里)
        // 注意：这里使用 Buffer 的绝对地址
        context.setDataReference(reqBuf.address(), reqBytes.length);
    }

    private void handleFailure(SimulationContext context, int errorCode) {
        context.setStatus(errorCode);
        int failIndex = this.transitionContexts.size() > 1 ? 1 : 0;
        context.setNextTransitionIndex(failIndex);
    }

    public String getTemplateRef() { return templateRef; }

    @Override
    protected void printExecutionDetails(SimulationContext context) {
        System.out.printf("    [Debug] IO Result: Status=%d, Len=%d%n",
                context.getStatus(), context.getDataLength());
    }
}