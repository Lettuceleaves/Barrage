package com.barrage.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仿真引擎核心 (虚拟线程版)。
 * <p>
 * 该引擎封装了 Java 21 Project Loom 的虚拟线程模型。
 * 它不再维护重量级的 OS 线程池，而是为每一个虚拟用户 (Virtual User) 分配一个独立的
 * 虚拟线程 (Virtual Thread)。
 * <p>
 * <b>核心能力：</b>
 * 能够轻松启动数百万个并发任务，底层仅占用少量 Carrier Threads (OS 线程)，
 * 彻底解决了传统模型中 "1 User = 1 Thread" 导致的内存溢出和调度开销问题。
 */
@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public final class SimulationEngine {

    private final int groupCount; // N
    private final int usersPerGroup; // M
    private final long totalAgents;

    // 核心：虚拟线程执行器
    // 不同于 CachedThreadPool，它是 "Thread-Per-Task"，但这里的 Thread 是虚拟的、廉价的。
    private ExecutorService vThreadExecutor;

    private volatile boolean running = false;
    private final AtomicInteger activeAgents = new AtomicInteger(0);

    /**
     * @param groupCount    组数 (N)
     * @param usersPerGroup 每组用户数 (M)
     */
    public SimulationEngine(int groupCount, int usersPerGroup) {
        this.groupCount = groupCount;
        this.usersPerGroup = usersPerGroup;
        this.totalAgents = (long) groupCount * usersPerGroup;
    }

    public void start() {
        if (running) return;
        running = true;

        System.out.printf(">>> [Engine] Spawning %d Virtual Threads (N=%d, M=%d)...%n",
                totalAgents, groupCount, usersPerGroup);

        // 1. 创建虚拟线程工厂
        // name: "sim-g{N}-u{M}" 格式，方便调试观察
        ThreadFactory vFactory = Thread.ofVirtual()
                .name("sim-agent-", 0)
                .factory();

        // 2. 初始化执行器
        // 这是 Java 21 的魔法：为每个提交的任务创建一个新的虚拟线程
        this.vThreadExecutor = Executors.newThreadPerTaskExecutor(vFactory);

        // 3. 瞬间分发所有任务
        for (int g = 0; g < groupCount; g++) {
            for (int u = 0; u < usersPerGroup; u++) {
                final int finalG = g;
                final int finalU = u;

                // 提交任务 -> 立即产生一个 Virtual Thread
                vThreadExecutor.submit(() -> userLifecycle(finalG, finalU));
            }
        }
    }

    /**
     * 虚拟用户的生命周期逻辑。
     * 这里的代码运行在 Virtual Thread 上。
     */
    private void userLifecycle(int groupId, int userId) {
        activeAgents.incrementAndGet();
        try {
            // --- 验证点 ---
            // 打印一下当前的线程信息，确认它是 VirtualThread
            if (groupId == 0 && userId == 0) {
                System.out.println(">>> [Check] First Agent Running on: " + Thread.currentThread());
                // 输出示例: VirtualThread[#21, sim-agent-0]/runnable@ForkJoinPool-1-worker-1
            }
            System.out.println("Hello Barage");

            // 模拟业务循环
            while (running) {
                // 在虚拟线程中，Thread.sleep 不会阻塞 OS 线程，只会挂起虚拟线程 (Unmount)
                // 这意味着哪怕 sleep 1秒，底层的 Carrier Thread 也可以去处理别的用户
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // 这里未来填入业务逻辑...
            }
        } finally {
            activeAgents.decrementAndGet();
        }
    }

    public void shutdown() {
        running = false;
        if (vThreadExecutor != null) {
            System.out.println(">>> [Engine] Shutting down virtual threads...");
            vThreadExecutor.shutdownNow();
            try {
                // 等待虚拟线程卸载
                if (!vThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println(">>> [Engine] Force killed remaining agents.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println(">>> [Engine] Stopped.");
    }

    // 用于监控存活数
    public int getActiveAgentCount() {
        return activeAgents.get();
    }
}