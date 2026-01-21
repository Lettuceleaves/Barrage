package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.util.List;

/**
 * 图节点基类 (Graph Node)。
 * <p>
 * 所有业务节点的抽象父类。采用了 <b>模板方法模式 (Template Method)</b> 来统一处理
 * 节点的通用逻辑（如性能打点、调试日志、上下文检查等），将具体的业务实现下放给子类。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 * @see HttpNode
 * @see ConditionNode
 * @see ChanceNode
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
     * [Template Method] 执行节点的标准流程。
     * <p>
     * 包含以下步骤：
     * <ol>
     * <li>Debug 日志 (Enter Node)。</li>
     * <li>记录开始时间。</li>
     * <li>调用抽象方法 {@link #run(SimulationContext)} 执行具体业务。</li>
     * <li>记录耗时并打印性能日志。</li>
     * <li>调用 {@link #printExecutionDetails(SimulationContext)} 打印业务细节。</li>
     * </ol>
     *
     * @param context 仿真上下文
     * @param isDebug 是否开启调试模式
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
     * [Abstract] 核心业务逻辑实现。
     * <p>
     * 子类必须实现此方法以完成具体的节点功能 (如发送 HTTP 请求、随机跳转、条件判断等)。
     * 执行完成后，子类负责设置 context 中的 nextTransitionIndex。
     *
     * @param context 仿真上下文
     */
    public abstract void run(SimulationContext context);

    /**
     * [Hook] 子类可覆盖此方法以打印特定的调试信息
     */
    protected void printExecutionDetails(SimulationContext context) {
        // 默认不打印额外信息
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public List<TransitionContext> getTransitionContexts() {
        return transitionContexts;
    }
}