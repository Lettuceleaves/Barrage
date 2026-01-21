package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 条件判断节点 (Condition Node).
 * <p>
 * 提供复杂的逻辑判断能力，支持根据上游节点的执行结果进行分支。
 * <p>
 * <b>判断逻辑 (优先级)：</b>
 * <ol>
 * <li><b>状态码匹配 (High Priority)：</b> 如果预期值 {@code expectedValue} 能解析为数字 (如
 * "200")，则直接与 {@link SimulationContext#getStatus()} 进行比较。这是极速的寄存器级比较。</li>
 * <li><b>内容匹配 (Deep Content)：</b> 如果状态码不匹配或预期值为字符串，则对响应 Body 进行二进制比对 (SIMD
 * 优化)。</li>
 * </ol>
 */
public class ConditionNode extends GraphNode {

    private final MemorySegment expectedSegment;
    private final long expectedStatus; // 缓存解析出的状态码，-1 表示非数字

    public ConditionNode(String name, List<TransitionContext> transitionContexts, byte[] expectedBytes) {
        super(name, "CONDITION", transitionContexts);

        if (transitionContexts == null || transitionContexts.size() < 2) {
            throw new IllegalArgumentException("ConditionNode requires 2 transitions: Match / Mismatch");
        }

        // 1. 准备 SIMD 对比用的 Segment (Heap Segment, safe to hold)
        this.expectedSegment = MemorySegment.ofArray(expectedBytes);

        // 2. 预判：这是否是一个状态码检查？
        long statusTemp = -1;
        try {
            String strVal = new String(expectedBytes, StandardCharsets.UTF_8).trim();
            statusTemp = Long.parseLong(strVal);
        } catch (Exception ignored) {
            // 说明预期值不是纯数字 (例如 "SUCCESS" 或 JSON片段)，那就不查状态码
        }
        this.expectedStatus = statusTemp;
    }

    public ConditionNode(String name, List<TransitionContext> transitionContexts, String expectedValue) {
        this(name, transitionContexts, expectedValue.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 获取预期的二进制数据段 (Getter)。
     *
     * @return 预期值的内存片段
     */
    public MemorySegment getExpectedSegment() {
        return expectedSegment;
    }

    @Override
    public void run(SimulationContext context) {
        boolean match = false;

        // =========================================================
        // 策略 A: 极速状态码检查 (CPU Register 操作，纳秒级)
        // =========================================================
        // 如果配置的是 "200"，且当前状态码确实是 200，直接通过
        if (expectedStatus != -1) {
            if (context.getStatus() == expectedStatus) {
                match = true;
            }
        }

        // =========================================================
        // 策略 B: 二进制内容检查 (SIMD Memory 操作)
        // =========================================================
        // 只有当状态码没匹配上（或者没配置状态码）时，才去比对 Body
        if (!match) {
            // 获取 Zero-Copy View (指向 IoLane RingBuffer)
            MemorySegment actual = context.getResponseBuffer();

            if (actual != null && actual.byteSize() > 0 && expectedSegment.byteSize() > 0) {
                // FFM mismatch: 返回 -1 表示完全相等 (byte-for-byte identical)
                // 这是一个 O(N/VectorSize) 的高效操作
                match = (actual.mismatch(expectedSegment) == -1);
            } else {
                // 边缘情况：两者都为空则视为相等
                match = (expectedSegment.byteSize() == 0 && (actual == null || actual.byteSize() == 0));
            }
        }

        // =========================================================
        // 3. 路由决策
        // =========================================================
        // Index 0 = Match (True)
        // Index 1 = Mismatch (False)
        context.setNextTransitionIndex(match ? 0 : 1);
    }

    @Override
    protected void printExecutionDetails(SimulationContext context) {
        long idx = context.getNextTransitionIndex();
        String result = (idx == 0) ? "MATCH (True)" : "MISMATCH (False)";
        System.out.printf("    [Debug] Check Result: %s%n", result);
    }
}