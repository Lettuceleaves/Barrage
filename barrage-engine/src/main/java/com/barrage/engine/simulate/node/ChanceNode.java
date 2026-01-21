package com.barrage.engine.simulate.node;

import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机分流节点 (Chance Node)。
 * <p>
 * 实现基于权重的概率跳转。类似于 Nginx 的 {@code split_clients} 或 A/B Testing 分流。
 * <p>
 * <b>性能优化：</b>
 * 构造时会将权重列表预处理为原生的 {@code int[]} 数组，并在运行时使用
 * 轮盘赌算法 (Roulette Wheel Selection) 进行 O(N) 复杂度的选择。
 * 配合 {@link java.util.concurrent.ThreadLocalRandom} 保证高并发下的随机数生成性能。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class ChanceNode extends GraphNode {

    // 原始配置 (保留用于 getters)
    private final List<Integer> weights;

    // --- 运行时优化字段 ---
    // 预计算的总权重，避免运行时重复计算
    private final int totalWeight;
    // 内部转为 int[] 数组，避免 List.get() 的拆箱和泛型开销
    private final int[] weightArray;

    public ChanceNode(String name, List<TransitionContext> transitionContexts, List<Integer> weights) {
        super(name, "CHANCE", transitionContexts);

        // 1. 基础校验
        if (weights.size() != transitionContexts.size()) {
            throw new IllegalArgumentException("Weights count must match transitions count for node: " + name);
        }
        this.weights = weights;

        // 2. 性能优化：预计算总权重并转为原生数组
        int sum = 0;
        this.weightArray = new int[weights.size()];
        for (int i = 0; i < weights.size(); i++) {
            Integer w = weights.get(i);
            if (w == null || w < 0) {
                throw new IllegalArgumentException("Weight must be non-negative");
            }
            this.weightArray[i] = w;
            sum += w;
        }
        this.totalWeight = sum;

        if (this.totalWeight <= 0) {
            throw new IllegalArgumentException("Total weight must be positive for node: " + name);
        }
    }

    public List<Integer> getWeights() {
        return weights;
    }

    @Override
    public void run(SimulationContext context) {
        // 1. 生成随机数 [0, totalWeight)
        // ThreadLocalRandom 性能远高于 Random，且无锁
        int randomVal = ThreadLocalRandom.current().nextInt(totalWeight);

        // 2. 轮盘赌算法 (Roulette Wheel Selection)
        // 算法逻辑：遍历权重，如果随机数小于当前权重，则命中；否则减去当前权重继续判断
        // 例如权重 [30, 70]，随机数 45
        // i=0(30): 45 < 30? False. randomVal = 45 - 30 = 15
        // i=1(70): 15 < 70? True. 命中 index 1

        int selectedIndex = 0;

        // 遍历原生 int 数组，极大减少 CPU 指令数
        for (int i = 0; i < weightArray.length; i++) {
            int weight = weightArray[i];
            if (randomVal < weight) {
                selectedIndex = i;
                break;
            }
            randomVal -= weight;
        }

        // 3. 将决策结果写入 Context (0GC)
        // 这里的 selectedIndex 对应 transitionContexts 的下标
        context.setNextTransitionIndex(selectedIndex);
    }

    @Override
    protected void printExecutionDetails(SimulationContext context) {
        System.out.printf("    [Debug] Roulette Selected Branch: #%d%n",
                context.getNextTransitionIndex());
    }

}