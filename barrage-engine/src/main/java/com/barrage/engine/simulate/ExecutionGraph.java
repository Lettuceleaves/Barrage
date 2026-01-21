package com.barrage.engine.simulate;

import com.barrage.engine.simulate.node.GraphNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行图 (Execution Graph).
 * <p>
 * 这是整个仿真场景的"地图"或"蓝图"。它定义了业务流程的拓扑结构，包含所有的节点 ({@link GraphNode}) 和它们之间的连线。
 * <p>
 * 特性：
 * <ul>
 * <li><b>无状态 (Stateless)：</b> 图本身不存储任何用户运行时的状态信息。</li>
 * <li><b>共享 (Shared)：</b> N 个虚拟用户并发共享同一个 {@code ExecutionGraph} 实例。</li>
 * <li><b>不可变 (Immutable-ish)：</b> 一旦构建完成 ({@code GraphLoader}
 * 加载后)，图结构通常不再改变。</li>
 * </ul>
 *
 * @see GraphNode
 * @see com.barrage.engine.simulate.config.GraphLoader
 */
public class ExecutionGraph {

    // 图的名称 (用于区分不同业务流程，如 "LoginFlow", "FlashSaleFlow")
    private final String graphName;

    // 核心数据结构：NodeID -> Node 实例的映射表
    // 使用 HashMap 保证运行时查找下一个节点的时间复杂度为 O(1)
    private final Map<String, GraphNode> nodeMap;

    // 起始节点的 ID (入口)
    private String startNodeId;

    public ExecutionGraph(String graphName) {
        this.graphName = graphName;
        this.nodeMap = new HashMap<>();
    }

    /**
     * 注册一个节点到图中。
     *
     * @param id   节点唯一标识符 (ID)
     * @param node 节点实例
     * @throws IllegalArgumentException 如果 ID 已存在
     */
    public void addNode(String id, GraphNode node) {
        // 可以在这里加个校验，防止 ID 重复覆盖
        if (nodeMap.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate Node ID in graph: " + id);
        }
        nodeMap.put(id, node);
    }

    /**
     * 设置图的入口点 ID。
     * <p>
     * 引擎启动时将从该 ID 对应的节点开始执行。
     *
     * @param startNodeId 起始节点 ID
     */
    public void setStartNodeId(String startNodeId) {
        this.startNodeId = startNodeId;
    }

    // --- 运行时高频调用的方法 (Hot Path) ---

    /**
     * 获取起始节点。
     * <p>
     * 虚拟用户"出生"或重置时调用此方法获取第一个执行节点。
     *
     * @return 起始节点实例，如果 ID 无效可能返回 null
     */
    public GraphNode getStartNode() {
        return nodeMap.get(startNodeId);
    }

    /**
     * 根据 ID 获取任意节点 (跳转时调用)
     * 
     * @param nodeId 目标节点 ID
     * @return 节点对象，如果未找到则返回 null
     */
    public GraphNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    /**
     * 获取图名称。
     *
     * @return 图名称 (e.g., "LoginFlow")
     */
    public String getGraphName() {
        return graphName;
    }

    /**
     * 获取图中所有节点的只读视图。
     * <p>
     * 用于调试、打印图结构或校验完整性。
     *
     * @return 不可修改的节点映射表
     */
    public Map<String, GraphNode> getAllNodes() {
        return Collections.unmodifiableMap(nodeMap);
    }
}