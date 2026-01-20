package com.barrage.engine.simulate;

import com.barrage.engine.simulate.node.GraphNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行图 (Execution Graph)
 * <p>
 * 这是整个仿真场景的"地图"。
 * 它是无状态的（Stateless）且不可变（Immutable-ish）的共享资源。
 * N 个虚拟用户将共享同一个 ExecutionGraph 实例，根据它来决定下一步怎么走。
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
     * 注册一个节点到图中
     */
    public void addNode(String id, GraphNode node) {
        // 可以在这里加个校验，防止 ID 重复覆盖
        if (nodeMap.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate Node ID in graph: " + id);
        }
        nodeMap.put(id, node);
    }

    /**
     * 设置图的入口点
     */
    public void setStartNodeId(String startNodeId) {
        this.startNodeId = startNodeId;
    }

    // --- 运行时高频调用的方法 (Hot Path) ---

    /**
     * 获取起始节点 (虚拟用户出生时调用)
     */
    public GraphNode getStartNode() {
        return nodeMap.get(startNodeId);
    }

    /**
     * 根据 ID 获取任意节点 (跳转时调用)
     * @param nodeId 目标节点 ID
     * @return 节点对象，如果未找到则返回 null
     */
    public GraphNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    /**
     * 获取图名称
     */
    public String getGraphName() {
        return graphName;
    }

    /**
     * (可选) 锁定图结构，防止运行时修改
     * 返回一个不可修改的 Map 视图，或者在 build 完之后调用此方法封印
     */
    public Map<String, GraphNode> getAllNodes() {
        return Collections.unmodifiableMap(nodeMap);
    }
}