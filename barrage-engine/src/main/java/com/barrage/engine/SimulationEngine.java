package com.barrage.engine;

import com.barrage.engine.simulate.ExecutionGraph;
import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.context.SimulationContext;
import com.barrage.engine.simulate.context.UserGroupContext;
import com.barrage.engine.simulate.node.GraphNode;
import com.barrage.engine.simulate.node.TerminalNode;
import com.barrage.engine.simulate.pool.NetworkInfrastructure;
import com.barrage.kernel.config.basic.BasicConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仿真引擎核心 (Refactored & Clean)
 */
@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public final class SimulationEngine {

    private final ExecutionGraph graph;
    private final List<UserGroupContext> userGroups;
    private NetworkInfrastructure networkInfra;
    private ExecutorService vThreadExecutor;
    private volatile boolean running = false;
    private final AtomicInteger activeAgents = new AtomicInteger(0);
    private static final long DEBUG_TARGET_UID = 100000;

    public SimulationEngine(ExecutionGraph graph) {
        this.graph = graph;
        this.userGroups = new ArrayList<>();
    }

    public void start() throws IOException {
        if (running) return;
        running = true;

        // ... (初始化逻辑保持不变，省略以节省篇幅) ...
        // ... 创建 NetworkInfrastructure, UserGroup, vThreadExecutor ...
        // 参考之前的初始化代码

        int totalUsers = 1; // Debug Mode
        int threads = BasicConfig.getCLIENT_THREADS();
        System.out.printf(">>> [Engine] Starting. Users: %d, IO Threads: %d%n", totalUsers, threads);

        this.networkInfra = new NetworkInfrastructure();
        UserGroupContext ctx = new UserGroupContext(graph, totalUsers, 100000);
        userGroups.add(ctx);
        ThreadFactory vFactory = Thread.ofVirtual().name("agent-", 0).factory();
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(vFactory);

        System.out.println(">>> [Engine] Spawning Virtual Threads...");
        for (UserGroupContext group : userGroups) {
            int count = group.getCapacity();
            for (int u = 0; u < count; u++) {
                int slotIndex = group.initNextUser();
                vThreadExecutor.submit(() -> userTask(group, slotIndex, networkInfra));
            }
        }
    }

    private void userTask(UserGroupContext group, int slotIndex, NetworkInfrastructure net) {
        activeAgents.incrementAndGet();
        SimulationContext context = new SimulationContext();
        context.wrap(group.getUserSlotsBlock(), slotIndex);
        context.setNetworkInfrastructure(net);

        long currentUid = context.getUserId();
        // 简化 Debug 判断
        boolean isDebug = (currentUid == DEBUG_TARGET_UID);

        if (isDebug) {
            System.out.printf(">>> [Debug] Agent %d STARTED.%n", currentUid);
        }

        GraphNode currentNode = graph.getStartNode();

        try {
            while (running) {
                // ========================================================
                // 1. 委托节点执行 (包含日志、IO、业务逻辑)
                // ========================================================
                currentNode.execute(context, isDebug);

                // ========================================================
                // 2. 终止检查
                // ========================================================
                long decisionIndex = context.getNextTransitionIndex();
                if (decisionIndex == TerminalNode.END_OF_FLOW_INDEX) {
                    if (isDebug) System.out.printf(">>> [Debug] Agent %d WORKFLOW END.%n", currentUid);
                    break;
                }

                // ========================================================
                // 3. 路由查找 (Engine 职责: 负责图的遍历)
                // ========================================================
                List<TransitionContext> transitions = currentNode.getTransitionContexts();
                if (transitions == null || decisionIndex < 0 || decisionIndex >= transitions.size()) {
                    System.err.printf("[Engine] Routing Error: Invalid index %d at node %s%n", decisionIndex, currentNode.getName());
                    break;
                }

                TransitionContext trans = transitions.get((int) decisionIndex);
                String nextNodeId = trans.getNext();

                if (isDebug) {
                    System.out.printf("    [Debug] Routing -> %s (Mode: %s)%n", nextNodeId, trans.getMode());
                }

                // ========================================================
                // 4. 模式处理 (Sleep)
                // ========================================================
                handleTransitionMode(trans, isDebug);

                // ========================================================
                // 5. 指针切换
                // ========================================================
                GraphNode nextNode = graph.getNode(nextNodeId);
                if (nextNode == null) {
                    System.err.printf("[Engine] Missing Node: %s%n", nextNodeId);
                    break;
                }
                currentNode = nextNode;
            }
        } catch (Exception e) {
            System.err.printf("❌ [Engine] Crash (UID: %d): %s%n", currentUid, e.getMessage());
            e.printStackTrace();
        } finally {
            activeAgents.decrementAndGet();
        }
    }

    private void handleTransitionMode(TransitionContext trans, boolean isDebug) {
        if ("WAIT_FIXED".equals(trans.getMode())) {
            long wait = parseLongSafely(trans.getValue(), 0);
            if (wait > 0) {
                if (isDebug) System.out.printf("    [Debug] Sleeping %d ms...%n", wait);
                try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
            }
        }
    }

    private long parseLongSafely(String val, long def) {
        try { return Long.parseLong(val); } catch (Exception e) { return def; }
    }

    // shutdown(), getActiveAgentCount() ... 保持不变
    public void shutdown() { /* ... */ }
    public int getActiveAgentCount() { return activeAgents.get(); }
}