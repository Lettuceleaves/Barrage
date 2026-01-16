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
 * Barrage CLI 的交互式压测任务配置向导。
 * <p>
 * 该类实现了 "Benchmark Launch Wizard" 流程，引导用户通过分步菜单完成压测任务的配置。
 * 它是从 CLI 界面进入核心压测引擎 (Engine) 的主要入口之一，负责构建 {@link LaunchContext}。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>双模式配置：</b> 支持从配置文件 (http.toml) 快速加载模板，或选择手动构造模式。</li>
 * <li><b>动态负载预览：</b> 在确认阶段，会预先计算 HTTP 报文的字节大小 (Payload Size)，帮助用户评估带宽压力。</li>
 * <li><b>安全确认机制：</b> 在正式启动引擎前提供参数汇总回显，防止误操作。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 该类为无状态工具类，虽然内部操作涉及阻塞式 I/O (等待用户输入)，但静态方法本身不持有共享状态。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class BenchmarkWizard implements Ansi {

    /**
     * 执行向导流程并构建启动上下文。
     * <p>
     * 流程包含四个阶段：
     * <ol>
     * <li><b>数据源选择：</b> 决定使用预设模板 (Template Mode) 还是手动输入 (Manual Mode)。</li>
     * <li><b>参数配置：</b> 设置目标 QPS (Target QPS) 等运行时参数。</li>
     * <li><b>任务确认：</b> 展示目标 IP、模式、报文大小及限流阈值，等待用户确认。</li>
     * <li><b>上下文构建：</b> 实例化 {@link LaunchContext} 供引擎使用。</li>
     * </ol>
     *
     * @param t 终端交互接口，用于绘制菜单和读取用户输入
     * @return 构建好的启动上下文 {@link LaunchContext}；如果用户在确认阶段选择取消 (N)，则返回 {@code null}
     */
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

    /**
     * 加载活跃模板以进行预览。
     * <p>
     * 这是一个辅助方法，用于在不启动引擎的情况下，临时构建一个 {@link HttpTemplate} 对象，
     * 以便计算序列化后的报文大小 (bytes)。
     *
     * @param templateName 模板名称，对应配置文件中的键
     * @param ip           目标 IP 地址
     * @param port         目标端口
     * @return 填充好数据的 HTTP 模板对象；如果模板未找到，返回默认的 GET 请求模板
     */
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