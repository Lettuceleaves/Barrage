package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.ExecutionMode;
import com.barrage.cli.interaction.Terminal;
import com.barrage.cli.model.LaunchContext;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;
import com.barrage.protocol.HTTP.HttpTemplate;
import com.barrage.protocol.datasource.DataSourceType;

import java.util.List;

/**
 * 模式 1: 交互式压测启动器
 * <p>
 * 简化模式：
 * 1. FILE: 从当前选中的 http.toml 模板加载。
 * 2. CONSOLE: 启动后在控制台手动输入字段。
 */
public class BenchmarkWizard implements Ansi {

    public static LaunchContext run(Terminal t) {
        t.clear();
        t.section(BLUE + "Benchmark Launch Wizard" + RESET);

        // --- 1. 选择数据源加载方式 ---
        t.info("Step 1: Select Message Source");
        t.info("  1. " + CYAN + "Template Mode" + RESET + " (From http.toml: " + BasicConfig.getACTIVE_TEMPLATE_NAME() + ")");
        t.info("  2. " + CYAN + "Manual Mode" + RESET + "   (Interactive console input)");
        t.line();

        String sourceChoice = t.readOption("Select Source", "1", "1", "2");

        DataSourceType selectedType;
        String sourceValue;
        long msgSize = 0;

        if (sourceChoice.equals("1")) {
            // A. FILE 模式：绑定到当前活跃模板
            selectedType = DataSourceType.FILE;
            sourceValue = BasicConfig.getACTIVE_TEMPLATE_NAME();

            // 预览报文大小
            HttpTemplate temp = loadActiveTemplate(sourceValue, BasicConfig.getIP(), BasicConfig.getPORT());
            msgSize = temp.toBytes().length;
        } else {
            // B. CONSOLE 模式：手动输入
            selectedType = DataSourceType.CONSOLE;
            sourceValue = "MANUAL_BUILD"; // 仅作标识
        }

        // --- 2. 运行时参数 ---
        t.line();
        t.info("Step 2: Runtime Parameters");
        long qps = t.readLong("Target QPS", 10000);

        // --- 3. 最终确认回显 ---
        t.clear();
        t.section(YELLOW + "Confirm Mission Parameters" + RESET);
        t.info("  Target   : " + GREEN + BasicConfig.getIP() + ":" + BasicConfig.getPORT() + RESET);
        t.info("  Mode     : " + CYAN + (selectedType == DataSourceType.FILE ? "Template" : "Manual Builder") + RESET);
        if (selectedType == DataSourceType.FILE) {
            t.info("  Template : " + CYAN + sourceValue + RESET);
            t.info("  Payload  : " + CYAN + msgSize + " bytes" + RESET);
        }
        t.info("  Limit    : " + CYAN + (qps + " QPS") + RESET);
        t.line();

        String confirm = t.readOption("Launch Attack? (Y/n)", "Y", "Y", "n", "y", "N");
        if ("n".equalsIgnoreCase(confirm)) return null;

        t.info("\n>>> Initializing Engine..." + RESET);

        // --- 4. 构建启动上下文 ---
        // 重要：确保 LaunchContext 构造函数接收 qps (long)
        return new LaunchContext(
                ExecutionMode.STRESS_TEST,
                BasicConfig.getIP(),
                BasicConfig.getPORT(),
                selectedType,
                sourceValue,
                qps
        );
    }

    private static HttpTemplate loadActiveTemplate(String templateName, String ip, int port) {
        HttpTemplate tpl = new HttpTemplate();
        tpl.setHost(ip);
        tpl.setPort(port);
        if (templateName == null) {
            tpl.setMethod("GET");
            tpl.setPath("/");
            return tpl;
        }
        List<String> details = TemplateConfig.get(templateName);
        if (details != null && details.size() >= 4) {
            tpl.setMethod(details.get(0));
            tpl.setPath(details.get(1));
            tpl.setBody(details.get(2));
            tpl.setHeaders(details.get(3));
        }
        return tpl;
    }
}