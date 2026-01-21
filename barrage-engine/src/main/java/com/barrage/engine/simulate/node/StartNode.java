package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.util.List;

/**
 * 起始节点 (Start Node).
 * <p>
 * 执行图的固定入口点。任何虚拟用户的生命周期都从此节点开始。
 * <p>
 * <b>职责：</b>
 * <ol>
 * <li>作为图的唯一入口。</li>
 * <li>无业务逻辑 (No-op)，仅起到导流作用。</li>
 * <li>将用户立即导向配置的第一个业务节点 (Index 0)。</li>
 * </ol>
 */
public class StartNode extends GraphNode {

    public StartNode(String name, List<TransitionContext> transitionContexts) {
        // Mode 固定为 "START"
        super(name, "START", transitionContexts);

        // 强校验：Start 节点必须有且仅有一个出口，否则图无法启动
        if (transitionContexts == null || transitionContexts.isEmpty()) {
            throw new IllegalArgumentException(
                    "StartNode must have at least 1 transition (the entry point of the flow).");
        }
    }

    /**
     * 执行逻辑
     * 
     * @param context 仿真上下文
     */
    @Override
    public void run(SimulationContext context) {
        // 逻辑极简：
        // StartNode 是直通的，不进行计算，不产生数据。
        // 直接设置 Next Index = 0，引擎会在下一次循环跳到 transitionContexts.get(0).getNext()
        context.setNextTransitionIndex(0);
    }
}