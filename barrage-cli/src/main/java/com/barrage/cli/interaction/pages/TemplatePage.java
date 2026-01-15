package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.kernel.config.template.TemplateConfig;

import java.util.List;

/**
 * HTTP 报文模板选择页面。
 * <p>
 * 职责：展示磁盘配置文件(http.toml)中的模板列表，供用户选择。
 * 选择后立即更新并持久化到 BasicConfig (config.toml)。
 */
public class TemplatePage implements Ansi {

    /**
     * 打开模板选择页面 (入口方法)
     *
     * @param t 终端工具
     * @return 用户选中的模板名称 (Template Name)；如果用户取消或列表为空，返回 null。
     */
    public static String open(Terminal t) {
        // 1. 获取所有模板名称
        List<String> names = TemplateConfig.getList();

        // 2. 空列表处理
        if (names == null || names.isEmpty()) {
            t.warn("No templates found in configuration.");
            t.info("Please configure [[templates]] in your http.toml file.");
            t.pause();
            return null;
        }

        while (true) {
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
                return null;
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

            return selectedName;
        }
    }

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
                    truncate(path, 25),
                    GRAY, name, RESET);
            t.info(line);
        }
    }

    private static String truncate(String str, int maxWidth) {
        if (str == null) return "";
        if (str.length() <= maxWidth) return str;
        return str.substring(0, maxWidth - 3) + "...";
    }
}