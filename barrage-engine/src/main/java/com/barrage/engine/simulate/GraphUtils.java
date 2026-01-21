package com.barrage.engine.simulate;

import com.barrage.engine.simulate.ExecutionGraph;
import com.barrage.engine.simulate.node.*;
import com.barrage.engine.simulate.TransitionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行图工具类。
 * <p>
 * 提供对 {@link ExecutionGraph} 的静态辅助方法，主要功能包括：
 * <ul>
 * <li><b>校验 (Validation)：</b> 检查图的完整性 (断链检查、入口检查)。</li>
 * <li><b>可视化 (Visualization)：</b> 在控制台打印图的树状结构或详细属性。</li>
 * </ul>
 */
public class GraphUtils {

    /**
     * 校验图的合法性。
     * <p>
     * 执行以下检查：
     * <ol>
     * <li><b>入口检查：</b> 起始节点 (StartNode) 必须存在且 ID 有效。</li>
     * <li><b>连通性检查：</b> 遍历所有节点的 Transition，确保指向的 next 节点 ID 都真实存在于图中。</li>
     * </ol>
     *
     * @param graph 要校验的执行图
     * @throws IllegalStateException 如果校验失败，抛出包含详细错误信息的异常
     */
    public static void validate(ExecutionGraph graph) {
        List<String> errors = new ArrayList<>();

        // 1. 检查 StartNode
        if (graph.getStartNode() == null) {
            errors.add("❌ Critical: Start node is missing or ID is invalid.");
        }

        // 2. 遍历所有节点检查连通性
        for (GraphNode node : graph.getAllNodes().values()) {
            if (node.getTransitionContexts() == null)
                continue;

            for (TransitionContext ctx : node.getTransitionContexts()) {
                String nextId = ctx.getNext();

                // 如果 next 为空，说明是终点或断开，视具体逻辑而定，这里假设如果不为空则必须有效
                if (nextId != null && !nextId.isEmpty()) {
                    if (graph.getNode(nextId) == null) {
                        errors.add(String.format("❌ Broken Link: Node '%s' (%s) points to missing node '%s'",
                                node.getName(), node.getClass().getSimpleName(), nextId));
                    }
                }
            }
        }

        // 3. 抛出汇总异常
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n>>> Graph Validation Failed for '").append(graph.getGraphName()).append("' <<<\n");
            errors.forEach(e -> sb.append(e).append("\n"));
            throw new IllegalStateException(sb.toString());
        }

        System.out.println("✅ Graph '" + graph.getGraphName() + "' passed validation.");
    }

    /**
     * 在控制台打印图的结构树 (从 StartNode 开始)。
     * <p>
     * 使用类似于 {@code tree} 命令的格式展示节点层级关系和流转模式。
     * 具备循环引用检测能力，防止无限递归。
     *
     * @param graph 要打印的执行图
     */
    public static void printGraph(ExecutionGraph graph) {
        System.out.println("\n=== Execution Graph Visualization: " + graph.getGraphName() + " ===");

        GraphNode startNode = graph.getStartNode();
        if (startNode == null) {
            System.out.println("(Empty Graph or Invalid Start Node)");
            return;
        }

        // 使用 Set 防止循环引用导致无限递归打印
        Set<String> visitedPath = new HashSet<>();
        printNodeRecursive(graph, startNode, "", true, visitedPath);
        System.out.println("======================================================\n");
    }

    /**
     * 递归打印节点辅助方法。
     *
     * @param graph  执行图引用
     * @param node   当前节点
     * @param prefix 前缀字符 (用于缩进和连接线)
     * @param isTail 是否是当前层级的最后一个元素 (决定使用 └── 还是 ├──)
     * @param path   当前的遍历路径 (用于检测循环回路)
     */
    private static void printNodeRecursive(ExecutionGraph graph, GraphNode node, String prefix, boolean isTail,
            Set<String> path) {
        // 1. 打印当前节点
        System.out.println(prefix + (isTail ? "└── " : "├── ") + formatNode(node));

        // 2. 循环检测
        if (path.contains(node.getName())) {
            System.out.println(prefix + (isTail ? "    " : "│   ") + "    (⟳ Loop back to " + node.getName() + ")");
            return;
        }

        // 将当前节点加入路径（只在当前递归栈有效，为了检测回环）
        // 注意：如果是单纯想遍历打印所有分支，这里不需要 copy set，但为了展示路径回环，我们需要 path 栈
        path.add(node.getName());

        List<TransitionContext> transitions = node.getTransitionContexts();
        if (transitions != null && !transitions.isEmpty()) {
            for (int i = 0; i < transitions.size(); i++) {
                TransitionContext ctx = transitions.get(i);
                GraphNode nextNode = graph.getNode(ctx.getNext());
                boolean isLastChild = (i == transitions.size() - 1);

                // 打印连线信息 (Mode)
                String linePrefix = prefix + (isTail ? "    " : "│   ");
                String arrowInfo = String.format("[%s] ➜ ", ctx.getMode());

                if (nextNode != null) {
                    // 递归打印子节点
                    // 注意：这里我们传递一个新的 Set 或者回溯，这里选择简单的回溯逻辑
                    printNodeRecursive(graph, nextNode, linePrefix, isLastChild, new HashSet<>(path));
                } else if (ctx.getNext() != null) {
                    // 打印缺失的节点 (理论上 validate 会拦截，但为了健壮性)
                    System.out.println(linePrefix + (isLastChild ? "└── " : "├── ") + "MISSING_NODE: " + ctx.getNext());
                } else {
                    // 终点
                    System.out.println(linePrefix + (isLastChild ? "└── " : "├── ") + "END");
                }
            }
        }
    }

    public static void printDetail(ExecutionGraph graph) {
        System.out.println("\n=== Graph Details: " + graph.getGraphName() + " ===");
        System.out.println("Entry Node: " + graph.getStartNode().getName()); // 假设 ExecutionGraph 加了
                                                                             // getStartNodeId()，如果没有就打印
                                                                             // getStartNode().getName()
        System.out.println("Total Nodes: " + graph.getAllNodes().size());
        System.out.println("------------------------------------------------------");

        // 排序，保证每次打印顺序一致
        graph.getAllNodes().values().stream()
                .sorted((n1, n2) -> n1.getName().compareTo(n2.getName())) // 假设 GraphNode 有 getId()，如果没有用 getName()
                .forEach(GraphUtils::printSingleNodeDetail);

        System.out.println("======================================================\n");
    }

    private static void printSingleNodeDetail(GraphNode node) {
        // 1. 打印通用头部
        System.out.printf("NODE [%s] (%s)\n", node.getName(), node.getType());

        // 如果 GraphNode 有 getId() 方法，这里打印 ID 更有用
        // System.out.printf(" ID: %s\n", node.getId());

        // 2. 根据类型打印特有属性
        if (node instanceof HttpNode httpNode) {
            System.out.printf("  Type: HTTP Action\n");
            System.out.printf("  Template Ref: %s\n", httpNode.getTemplateRef());

        } else if (node instanceof ConditionNode condNode) {
            System.out.printf("  Type: Conditional Logic\n");
            System.out.printf("  Target Var: %s\n", condNode.getExpectedSegment());

            // --- 修正开始 ---
            // 既然统一使用 MemorySegment，我们就把它转换回 String 来显示
            // 使用 %s 而不是 %d
            java.lang.foreign.MemorySegment seg = condNode.getExpectedSegment();

            if (seg != null && seg.byteSize() > 0) {
                // 将 MemorySegment 转回 byte[] 再转 String (假定是 UTF-8 配置)
                byte[] bytes = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                String displayVal = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

                System.out.printf("  Expected Value: \"%s\"\n", displayVal);
            } else {
                System.out.println("  Expected Value: (Empty/Null)");
            }
            // --- 修正结束 ---

        } else if (node instanceof ChanceNode chanceNode) {
            System.out.printf("  Type: Random Chance\n");
            System.out.printf("  Weights: %s\n", chanceNode.getWeights());

        } else if (node instanceof TerminalNode termNode) {
            System.out.printf("  Type: Terminal\n");
            System.out.printf("  Result Tag: %s\n", termNode.getResultTag());
            System.out.printf("  Save Context: %b\n", termNode.isSaveContext());
        }

        // 3. 打印出口 (Transitions)
        List<TransitionContext> transitions = node.getTransitionContexts();
        if (transitions != null && !transitions.isEmpty()) {
            System.out.println("  Transitions:");
            for (int i = 0; i < transitions.size(); i++) {
                TransitionContext ctx = transitions.get(i);
                String label = String.valueOf(i);

                // 为特定节点增加语义标签
                if (node instanceof ConditionNode) {
                    label = (i == 0) ? "MATCH (True)" : "MISMATCH (False)";
                } else if (node instanceof ChanceNode) {
                    // 安全起见防止越界
                    List<Integer> weights = ((ChanceNode) node).getWeights();
                    if (i < weights.size()) {
                        label = "Weight " + weights.get(i) + "%";
                    }
                }

                System.out.printf("    - [%s] --(%s)--> %s\n", label, ctx.getMode(), ctx.getNext());
            }
        } else {
            // 只有 Terminal 理论上应该没有出口
            if (!(node instanceof TerminalNode)) {
                System.out.println("  Transitions: (None - End of Flow?)");
            }
        }
        System.out.println("- - - - - - - - - - - - - - - - - - - - - - - - - - -");
    }

    private static String formatNode(GraphNode node) {
        // 输出格式: Name [Mode]
        // 比如: "Login Page [HTTP]"
        return String.format("\u001B[36m%s\u001B[0m \u001B[33m[%s]\u001B[0m", node.getName(), node.getType());
    }
}