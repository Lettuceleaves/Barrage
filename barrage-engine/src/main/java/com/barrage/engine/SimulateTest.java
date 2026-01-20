package com.barrage.engine;

import com.barrage.engine.simulate.ExecutionGraph;
// 请根据你实际放置 GraphLoader 的包名调整引用，之前建议是在 loader 包下
// 引用 GraphUtils (假设在 simulate 包或者 simulate.util 包下)
import com.barrage.engine.simulate.config.GraphLoader;
import com.barrage.kernel.config.basic.BasicConfig;

/**
 * 手动测试类：测试配置加载与图构建
 */
public class SimulateTest {

    public static void main(String[] args) {
        // 1. 确定配置路径
        // IDEA 中 System.getProperty("user.dir") 通常指向项目根目录
        BasicConfig.load();
        String fileName = "scenario.yaml";

        try {
            // 3. 调用核心加载器
            // (GraphLoader 内部通常已经调用了 validate 和 printGraph)
            ExecutionGraph graph = GraphLoader.load(BasicConfig.getConfigDir(), fileName);

            SimulationEngine simulationEngine = new SimulationEngine(graph);
            simulationEngine.start();

            // 4. 调用详细信息打印
            System.out.println("\n>>> 2. Detailed Inspection (printDetail):");

            // --- 核心修改：调用工具类打印完整详情 ---
//            GraphUtils.printDetail(graph);

            System.out.println("✅ TEST PASSED: Graph built successfully in memory.");

        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED: Exception occurred during graph build.");
            e.printStackTrace();
        }
    }
}