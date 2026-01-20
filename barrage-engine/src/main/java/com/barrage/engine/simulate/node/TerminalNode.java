package com.barrage.engine.simulate.node;


import com.barrage.engine.simulate.context.SimulationContext;

import java.util.Collections;

/**
 * 终止节点 (TerminalNode)
 * <p>
 * 职责：
 * 1. 标记当前虚拟用户执行结束。
 * 2. 写入最终结果状态码 (Status)。
 * 3. 将 NextTransitionIndex 设置为哨兵值 (如 -1)，中断引擎循环。
 */
public class TerminalNode extends GraphNode {

    public static final long END_OF_FLOW_INDEX = -1L;

    private final String resultTag;
    private final boolean saveContext;

    // 运行时优化：将 String tag 转换为 long hash 或 int code
    // 避免运行时操作 String
    private final long resultTagCode;

    public TerminalNode(String name, String resultTag, boolean saveContext) {
        // Mode 固定为 "TERMINAL"，无出口
        super(name, "TERMINAL", Collections.emptyList());
        this.resultTag = resultTag;
        this.saveContext = saveContext;

        // 预计算：将 "SUCCESS" 等字符串转为 HashCode 或者业务约定的 Int 值
        // 实际生产中建议使用 Registry 映射 (String -> int)，这里简单用 hashCode 演示
        this.resultTagCode = resultTag != null ? resultTag.hashCode() : 0;
    }

    public String getResultTag() { return resultTag; }
    public boolean isSaveContext() { return saveContext; }

    @Override
    public void run(SimulationContext context) {
        // ==========================================================
        // 1. 设置终止信号 (Sentinel Value)
        // ==========================================================
        // 引擎 Loop 检测到 Index == -1 时，会跳出 while 循环，
        // 并根据 IDLE 策略决定是休眠还是立即重置开始下一次模拟。
        context.setNextTransitionIndex(END_OF_FLOW_INDEX);

        // ==========================================================
        // 2. 写入结果状态 (For Metrics)
        // ==========================================================
        // 将结果标记写入 Context 的 Status 字段 (UserSlotLayout.OFFSET_STATUS)
        // 监控线程会异步读取这块内存来统计 TPS 和 成功率。
        //
        // 假设 context 有 setStatus(long) 方法：
        // 高 32 位可以存标志位 (如 saveContext)，低 32 位存 resultTagCode
        long statusPayload = resultTagCode;
        if (saveContext) {
            // 举例：第 63 位置 1 表示需要 Dump 现场
            statusPayload |= (1L << 63);
        }

        // 注意：你需要给 SimulationContext 加这个方法
        // context.setStatus(statusPayload);

        // (为了演示先注释掉，取决于你 Context 是否已实现 setStatus)
        // context.setStatus(resultTagCode);
    }

    @Override
    protected void printExecutionDetails(SimulationContext context) {
        System.out.printf("    [Debug] Terminal Reached. Result Tag: %s%n", getResultTag());
    }
}