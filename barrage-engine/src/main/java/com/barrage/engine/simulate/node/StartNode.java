package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.util.List;

/**
 * 起始节点 (StartNode)
 * <p>
 * 职责：
 * 1. 作为图的固定入口。
 * 2. 将新进来的虚拟用户立即导向第一个业务节点 (通常是 Index 0)。
 * 3. (可选) 这里也可以用来重置一些上下文计数器，但通常引擎在进入 Start 之前已经 reset 过了。
 */
public class StartNode extends GraphNode {

    public StartNode(String name, List<TransitionContext> transitionContexts) {
        // Mode 固定为 "START"
        super(name, "START", transitionContexts);

        // 强校验：Start 节点必须有且仅有一个出口，否则图无法启动
        if (transitionContexts == null || transitionContexts.isEmpty()) {
            throw new IllegalArgumentException("StartNode must have at least 1 transition (the entry point of the flow).");
        }
    }

    /**
     * 执行逻辑
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