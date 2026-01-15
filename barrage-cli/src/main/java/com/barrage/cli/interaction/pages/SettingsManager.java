package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;
import com.barrage.kernel.config.basic.BasicConfig;

public class SettingsManager implements Ansi {

    public static void open(Terminal t) {
        while (true) {
            t.clear();
            t.section(PURPLE + "Kernel Configuration Settings" + RESET);

            // 实时回显当前内存中的配置状态
            t.info(YELLOW + "[Network]" + RESET);
            t.info("  1. Target IP        : " + CYAN + BasicConfig.getIP() + RESET);
            t.info("  2. Target Port      : " + CYAN + BasicConfig.getPORT() + RESET);

            t.info(YELLOW + "[Engine Threads]" + RESET);
            t.info("  3. Server Threads   : " + CYAN + BasicConfig.getSERVER_THREADS() + RESET);
            t.info("  4. Client Threads   : " + CYAN + BasicConfig.getCLIENT_THREADS() + RESET);
            t.info("  5. Conns Per Worker : " + CYAN + BasicConfig.getCONNS_PER_CLIENT() + RESET);

            t.info(YELLOW + "[io_uring Performance]" + RESET);
            t.info("  6. In-Flight Depth  : " + CYAN + BasicConfig.getIN_FLIGHT() + RESET);
            t.info("  7. Queue Depth (2^n): " + CYAN + BasicConfig.getQUEUE_DEPTH() + RESET);
            t.info("  8. Batch Size       : " + CYAN + BasicConfig.getBATCH_SIZE() + RESET);
            t.info("  9. Read Buffer (Sz) : " + CYAN + BasicConfig.getREAD_SZ() + RESET);

            t.line();
            t.info(GREEN + "  S. Save & Reload Configuration" + RESET);
            t.info("  B. Back to Main Menu");
            t.line();

            String choice = t.readOption("Select Setting to modify", "B",
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "S", "B", "s", "b");

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
                    case "6":
                        BasicConfig.setIN_FLIGHT(t.readInt("In-Flight Depth", BasicConfig.getIN_FLIGHT()));
                        break;
                    case "7":
                        // 这里内部会触发 BasicConfig 的 2的幂次校验
                        BasicConfig.setQUEUE_DEPTH(t.readInt("Queue Depth", BasicConfig.getQUEUE_DEPTH()));
                        break;
                    case "8":
                        BasicConfig.setBATCH_SIZE(t.readInt("Batch Size", BasicConfig.getBATCH_SIZE()));
                        break;
                    case "9":
                        BasicConfig.setREAD_SZ(t.readInt("Read Buffer Size", BasicConfig.getREAD_SZ()));
                        break;
                    // SettingsManager.java 内部
                    case "S":
                        try {
                            t.info("正在持久化到磁盘...");
                            BasicConfig.save();      // 写入 config.toml

                            t.info("正在重新加载并校验...");
                            BasicConfig.load();      // 强制刷新 BasicConfig 内存

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