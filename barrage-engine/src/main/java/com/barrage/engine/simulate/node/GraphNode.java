package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.util.List;

/**
 * 图节点基类 (Refactored)
 * 引入模板方法模式处理 Debug 逻辑。
 */
public abstract class GraphNode {

    protected final String name;
    protected final String type;
    protected final List<TransitionContext> transitionContexts;

    public GraphNode(String name, String type, List<TransitionContext> transitionContexts) {
        this.name = name;
        this.type = type;
        this.transitionContexts = transitionContexts;
    }

    /**
     * [Template Method] 统一执行入口
     * 包含：日志、计时、业务逻辑调用、决策打印
     */
    public void execute(SimulationContext context, boolean isDebug) {
        long uid = context.getUserId();

        // 1. 通用进入日志
        if (isDebug) {
            System.out.printf(">>> [Debug] Agent %d ENTER Node: [%s] Type: %s%n",
                    uid, name, type);
        }

        // 2. 计时 & 执行业务
        long start = System.nanoTime();
        run(context); // 调用子类实现
        long duration = System.nanoTime() - start;

        // 3. 通用退出日志 & 钩子方法
        if (isDebug) {
            System.out.printf("    [Debug] Time: %.2f ms%n", duration / 1_000_000.0);

            // Hook: 让子类打印特定细节 (如 HTTP Status, Random Result)
            printExecutionDetails(context);

            // 打印决策结果
            long idx = context.getNextTransitionIndex();
            System.out.printf("    [Debug] Decision Index: %d%n", idx);
        }
    }

    /**
     * [Abstract] 核心业务逻辑
     */
    public abstract void run(SimulationContext context);

    /**
     * [Hook] 子类可覆盖此方法以打印特定的调试信息
     */
    protected void printExecutionDetails(SimulationContext context) {
        // 默认不打印额外信息
    }

    // Getters
    public String getName() { return name; }
    public String getType() { return type; }
    public List<TransitionContext> getTransitionContexts() { return transitionContexts; }
}