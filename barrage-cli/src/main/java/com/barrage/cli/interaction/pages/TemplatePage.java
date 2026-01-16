package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;

import java.util.List;

/**
 * HTTP 请求报文模板的选择与切换界面。
 * <p>
 * 该类负责桥接模板定义层 ({@code http.toml}) 与运行时配置层 ({@code config.toml})。
 * 它提供了一个可视化的列表，允许用户预览并从预定义的模板集合中选择一个作为当前活跃模板 (Active Template)。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>智能预览 (Smart Preview)：</b> 在列表中不仅仅显示模板名称，还会解析并展示请求方法 (Method) 和路径 (Path) 摘要，
 * 帮助用户快速识别业务场景。</li>
 * <li><b>即时持久化 (Immediate Persistence)：</b> 用户的选择不仅会更新内存中的 {@link BasicConfig}，
 * 还会立即触发磁盘写入操作，确保下次启动时能自动恢复该选择。</li>
 * <li><b>容错处理：</b> 当未配置任何模板时，提供友好的引导提示而非直接报错。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 该类涉及对全局静态配置 {@link BasicConfig} 的写操作。虽然在 CLI 的串行交互流程中是安全的，
 * 但不应在压测引擎运行时并发调用此界面。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class TemplatePage implements Ansi {

    /**
     * 打开模板选择交互菜单 (入口方法)。
     * <p>
     * 执行流程：
     * <ol>
     * <li>加载并校验 {@link TemplateConfig} 中的模板列表。</li>
     * <li>若列表为空，输出配置引导提示并返回。</li>
     * <li>渲染带有详细预览信息的选项列表。</li>
     * <li>读取用户输入并更新 {@link BasicConfig#setACTIVE_TEMPLATE_NAME(String)}。</li>
     * <li>尝试将变更回写到磁盘配置文件 (config.toml)。</li>
     * </ol>
     *
     * @param t 终端交互接口，用于绘制界面和读取输入
     * @implNote 即使磁盘保存失败 (如权限不足)，内存中的配置依然会更新，且方法会以警告形式提示用户，
     * 而不会阻断操作流程。
     */
    public static void open(Terminal t) {
        // 1. 获取所有模板名称
        List<String> names = TemplateConfig.getList();

        // 2. 空列表处理
        if (names.isEmpty()) {
            t.warn("No templates found in configuration.");
            t.info("Please configure [[templates]] in your http.toml file.");
            t.pause();
            return;
        }

        t.clear();
        t.section(PURPLE + "HTTP Request Templates" + RESET);

        // 3. 渲染列表
        renderList(t, names);

        t.line();
        t.info("  B. Back / Cancel");
        t.line();

        // 4. 构建选项范围
        String[] validOptions = new String[names.size() + 2];
        for (int i = 0; i < names.size(); i++) {
            validOptions[i] = String.valueOf(i + 1);
        }
        validOptions[names.size()] = "B";
        validOptions[names.size() + 1] = "b";

        // 5. 读取用户输入
        String choice = t.readOption("Select Template", "B", validOptions);

        if (choice.equalsIgnoreCase("B")) {
            return;
        }

        // 6. 处理选择
        int index = Integer.parseInt(choice) - 1;
        String selectedName = names.get(index);

        // --- 核心变更：更新并持久化配置 ---
        try {
            // A. 更新内存中的全局配置
            BasicConfig.setACTIVE_TEMPLATE_NAME(selectedName);

            // B. 持久化到 config.toml
            BasicConfig.save();

            t.success("Config Updated: Active Template -> " + selectedName);
        } catch (Exception e) {
            // 即使保存失败，运行时依然可以使用该选项，所以只警告不阻断
            t.warn("Selected successfully but failed to save config.toml: " + e.getMessage());
        }

    }

    /**
     * 渲染格式化的模板列表。
     * <p>
     * 遍历模板名称列表，提取每个模板的 Method 和 Path 进行格式化输出，
     * 形成如 {@code "1. [GET] /api/v1/users - MyTemplate"} 的视觉效果。
     *
     * @param t     终端接口
     * @param names 模板名称列表
     */
    private static void renderList(Terminal t, List<String> names) {
        t.info("Available Requests:");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);

            // 获取模板详情用于预览: [method, path, body, headers]
            List<String> rawData = TemplateConfig.get(name);

            String method = "UNK";
            String path = "/...";

            if (rawData != null && rawData.size() >= 2) {
                method = rawData.get(0);
                path = rawData.get(1);
            }

            // 格式化输出: " 1. [GET] /api/v1/list - TemplateName"
            String line = String.format("  %d. %s[%-4s]%s %-25s %s- %s%s",
                    (i + 1),
                    CYAN, method, RESET,
                    truncate(path),
                    GRAY, name, RESET);
            t.info(line);
        }
    }

    /**
     * 字符串截断辅助方法。
     * <p>
     * 用于 UI 对齐，确保过长的路径不会破坏列表的排版布局。
     * 超过 25 字符的字符串将被截断并添加 "..." 后缀。
     *
     * @param str 原始字符串
     * @return 处理后的字符串
     */
    private static String truncate(String str) {
        if (str == null) return "";
        if (str.length() <= 25) return str;
        return str.substring(0, 25 - 3) + "...";
    }
}