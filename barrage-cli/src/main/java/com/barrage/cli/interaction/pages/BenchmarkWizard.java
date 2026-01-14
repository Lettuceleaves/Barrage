package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.BasicConfig;
import com.barrage.protocol.datasource.DataSourceType;

/**
 * 模式 1: 交互式基准测试向导 (已精简，仅限压力测试模式)
 */
public class BenchmarkWizard implements Ansi {

    private static final String DEFAULT_FILE = "tmp/http_request.txt";

    public static LaunchContext run(Terminal t) {
        t.section(BLUE + "Stress Test Configuration" + RESET);

        // --- 1. 网络参数配置 (直接配置目标，不再询问拓扑) ---
        t.info("Target Mode: " + YELLOW + "Client Only (Remote/External Target)" + RESET);

        String ip = t.readString("Enter Target IP", "127.0.0.1");
        int port = t.readInt("Enter Port", 8080);

        // 同管同步全局配置
        BasicConfig.setIP(ip);
        BasicConfig.setPORT(port);

        // --- 2. 数据源配置 ---
        t.section(BLUE + "Data Source Selection" + RESET);
        t.info(YELLOW + "1. FILE" + RESET + "    (Path: " + DEFAULT_FILE + ")");
        t.info(YELLOW + "2. CONSOLE" + RESET + " (Manual Input)");

        String sourceChoice = t.readOption("Select Source", "1", "1", "2");

        DataSourceType sourceType;
        String sourceValue;

        if ("2".equals(sourceChoice)) {
            sourceType = DataSourceType.CONSOLE;
            sourceValue = "Enter HTTP Request Header (Empty line to finish): ";
        } else {
            sourceType = DataSourceType.FILE;
            sourceValue = DEFAULT_FILE;
        }

        t.info("\n>>> Configuration Completed. " + GREEN + "Launching Engine..." + RESET);

        // 这里的 startInternalServer 固定为 false
        // 在向导结束返回时
        return new LaunchContext(
                true,
                ExecutionMode.STRESS_TEST, // BenchmarkWizard 现在只做压测
                ip,
                port,
                sourceType,
                sourceValue
        );
    }
}