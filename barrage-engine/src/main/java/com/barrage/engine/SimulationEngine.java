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
 * <p>
 * {@code SimulationEngine} 是整个压测任务的调度中心和驱动器。它负责维护虚拟用户的生命周期、
 * 管理执行图 ({@link ExecutionGraph}) 的遍历、以及协调底层的网络基础设施
 * ({@link NetworkInfrastructure})。
 * <p>
 * 核心职责：
 * <ul>
 * <li><b>用户调度：</b> 使用虚拟线程 (Virtual Threads) 承载海量并发用户 (100万+)，实现高并发模拟。</li>
 * <li><b>图遍历执行：</b> 驱动虚拟用户在 {@link ExecutionGraph} 中流转，执行节点逻辑
 * ({@link GraphNode#execute})。</li>
 * <li><b>流量控制：</b> 通过 {@link UserGroupContext} 和 Ramp-up
 * 策略控制用户启动速率，防止瞬时流量风暴。</li>
 * <li><b>生命周期管理：</b> 提供 {@code start()} 和 {@code shutdown()}
 * 方法管理引擎的启动与优雅停机。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @since 2026/1
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

    /**
     * 启动仿真引擎。
     * <p>
     * 该方法会初始化网络基础设施，并在虚拟线程池中启动指定数量 (100万) 的虚拟用户。
     * 为了避免对目标系统造成瞬时冲击，采用分批启动 (Ramp-up) 策略。
     *
     * @throws IOException 如果网络基础设施初始化失败
     */
    public void start() throws IOException {
        running = true;
        this.networkInfra = new NetworkInfrastructure();
        int totalUsers = 1;

        UserGroupContext ctx = new UserGroupContext(graph, totalUsers, 0);
        userGroups.add(ctx);
        vThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agent-", 0).factory());

        System.out.println(">>> [Engine] Launching 1M Users with Ramp-up...");

        int batchSize = 1000; // 每批 1000 人
        for (int i = 0; i < totalUsers; i++) {
            int slotIndex = ctx.initNextUser();
            vThreadExecutor.submit(() -> userTask(ctx, slotIndex, networkInfra));

            // 每 1000 个用户休息 50ms，防止启动风暴打死目标服务器
            if (i % batchSize == 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                System.out.print("\r>>> Active: " + i);
            }
        }
    }

    /**
     * 单个虚拟用户的执行任务 (Agent Task)。
     * <p>
     * 这是运行在虚拟线程中的主循环逻辑。每个 Agent 包含一个独立的上下文 ({@link SimulationContext})，
     * 从 {@link ExecutionGraph#getStartNode()} 开始，根据节点逻辑和路由规则不断流转，
     * 直到遇到 {@link TerminalNode} 或发生异常。
     *
     * @param group     所属的用户组上下文
     * @param slotIndex 用户在组内的槽位索引
     * @param net       共享的网络基础设施
     */
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
                    if (isDebug)
                        System.out.printf(">>> [Debug] Agent %d WORKFLOW END.%n", currentUid);
                    break;
                }

                // ========================================================
                // 3. 路由查找 (Engine 职责: 负责图的遍历)
                // ========================================================
                List<TransitionContext> transitions = currentNode.getTransitionContexts();
                if (transitions == null || decisionIndex < 0 || decisionIndex >= transitions.size()) {
                    System.err.printf("[Engine] Routing Error: Invalid index %d at node %s%n", decisionIndex,
                            currentNode.getName());
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
                if (isDebug)
                    System.out.printf("    [Debug] Sleeping %d ms...%n", wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private long parseLongSafely(String val, long def) {
        try {
            return Long.parseLong(val);
        } catch (Exception e) {
            return def;
        }
    }

    // shutdown(), getActiveAgentCount() ... 保持不变
    public void shutdown() {
        /* ... */ }

    public int getActiveAgentCount() {
        return activeAgents.get();
    }
}