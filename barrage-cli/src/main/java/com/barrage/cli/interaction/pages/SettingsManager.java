package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;
import com.barrage.kernel.config.basic.BasicConfig;

/**
 * Barrage 内核全局参数配置管理器。
 * <p>
 * 该类提供了一个可视化的交互式仪表盘，允许用户在运行时查看和修改 {@link BasicConfig} 中的核心参数。
 * 它涵盖了从基础的网络设置（目标 IP/Port）到深度的 {@code io_uring} 性能调优选项。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>实时回显 (Live Reflection)：</b> 每次刷新界面都会直接从内存中读取 {@code BasicConfig} 的最新静态字段值，
 * 确保显示的状态与内核实际使用的配置完全一致。</li>
 * <li><b>深度调优能力：</b> 暴露了底层环形缓冲区 (Ring Buffer) 的关键参数（如 {@code Queue Depth}, {@code Batch Size}），
 * 允许高级用户针对特定硬件环境进行微调。</li>
 * <li><b>配置持久化：</b> 支持将当前内存中的修改保存到磁盘并在下次启动时自动加载，
 * 同时提供“保存并重载”功能以立即验证配置文件的合法性。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 该类直接修改全局静态变量。虽然在单线程 CLI 交互流程中是安全的，
 * 但如果在压测引擎运行期间并发调用此界面修改参数，可能会导致未定义的行为或数据竞争。
 * 建议仅在压测任务开始前使用。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class SettingsManager implements Ansi {

    /**
     * 打开配置管理仪表盘。
     * <p>
     * 进入一个阻塞式的读写循环，展示当前的配置列表。用户可以通过输入对应的数字序号修改特定参数，
     * 或选择保存/退出。
     * <p>
     * <b>配置项分类：</b>
     * <ul>
     * <li><b>Network:</b> 目标地址与端口。</li>
     * <li><b>Engine Threads & Stress:</b> 线程模型参数（Reactor 数量、连接池大小）及加压步长 (QPS Step)。</li>
     * <li><b>io_uring Performance:</b> 提交队列深度、批量提交大小 (Batch Size) 及读缓冲区大小。</li>
     * </ul>
     *
     * @param t 终端交互接口，用于渲染菜单和读取用户指令
     */
    public static void open(Terminal t) {
        while (true) {
            t.clear();
            t.section(PURPLE + "Kernel Configuration Settings" + RESET);

            // 实时回显当前内存中的配置状态
            t.info(YELLOW + "[Network]" + RESET);
            t.info("  1. Target IP        : " + CYAN + BasicConfig.getIP() + RESET);
            t.info("  2. Target Port      : " + CYAN + BasicConfig.getPORT() + RESET);

            t.info(YELLOW + "[Engine Threads & Stress]" + RESET); // 修改了标题
            t.info("  3. Server Threads   : " + CYAN + BasicConfig.getSERVER_THREADS() + RESET);
            t.info("  4. Client Threads   : " + CYAN + BasicConfig.getCLIENT_THREADS() + RESET);
            t.info("  5. Conns Per Worker : " + CYAN + BasicConfig.getCONNS_PER_CLIENT() + RESET);
            t.info("  6. QPS Step         : " + CYAN + BasicConfig.getSTEP() + RESET); // 新增显示

            t.info(YELLOW + "[io_uring Performance]" + RESET);
            t.info("  7. In-Flight Depth  : " + CYAN + BasicConfig.getIN_FLIGHT() + RESET); // 序号顺延
            t.info("  8. Queue Depth (2^n): " + CYAN + BasicConfig.getQUEUE_DEPTH() + RESET);
            t.info("  9. Batch Size       : " + CYAN + BasicConfig.getBATCH_SIZE() + RESET);
            t.info("  10. Read Buffer (Sz): " + CYAN + BasicConfig.getREAD_SZ() + RESET);

            t.line();
            t.info(GREEN + "  S. Save & Reload Configuration" + RESET);
            t.info("  B. Back to Main Menu");
            t.line();

            // 更新了可选项列表，增加了 "10"
            String choice = t.readOption("Select Setting to modify", "B",
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "S", "B", "s", "b");

            try {
                switch (choice.toUpperCase()) {
                    case "1":
                        BasicConfig.setIP(t.readInput("New IP", BasicConfig.getIP()));
                        break;
                    case "2":
                        BasicConfig.setPORT(t.readInt("New Port", BasicConfig.getPORT()));
                        break;
                    case "3":
                        BasicConfig.setSERVER_THREADS(t.readInt("Server Workers", BasicConfig.getSERVER_THREADS()));
                        break;
                    case "4":
                        BasicConfig.setCLIENT_THREADS(t.readInt("Client Workers", BasicConfig.getCLIENT_THREADS()));
                        break;
                    case "5":
                        BasicConfig.setCONNS_PER_CLIENT(t.readInt("Connections per Client", BasicConfig.getCONNS_PER_CLIENT()));
                        break;
                    case "6": // 新增：处理 Step 修改
                        BasicConfig.setSTEP(t.readInt("QPS Increase Step", BasicConfig.getSTEP()));
                        break;
                    case "7":
                        BasicConfig.setIN_FLIGHT(t.readInt("In-Flight Depth", BasicConfig.getIN_FLIGHT()));
                        break;
                    case "8":
                        BasicConfig.setQUEUE_DEPTH(t.readInt("Queue Depth", BasicConfig.getQUEUE_DEPTH()));
                        break;
                    case "9":
                        BasicConfig.setBATCH_SIZE(t.readInt("Batch Size", BasicConfig.getBATCH_SIZE()));
                        break;
                    case "10": // 顺延修改
                        BasicConfig.setREAD_SZ(t.readInt("Read Buffer Size", BasicConfig.getREAD_SZ()));
                        break;
                    case "S":
                        try {
                            t.info("正在持久化到磁盘...");
                            BasicConfig.save();

                            t.info("正在重新加载并校验...");
                            BasicConfig.load();

                            t.success("配置已保存并重载！");
                        } catch (Exception e) {
                            t.error("保存/加载失败: " + e.getMessage());
                        }
                        t.pause();
                        break;
                    case "B":
                        return;
                }
            } catch (Exception e) {
                t.error("Update Failed: " + e.getMessage());
                t.pause();
            }
        }
    }
}