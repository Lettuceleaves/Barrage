package com.barrage.kernel.config.template;

import com.barrage.kernel.config.basic.BasicConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TemplateConfig {

    private static final ObjectMapper mapper = new TomlMapper();

    /**
     * 获取 http.toml 中所有模板的 name 列表
     *
     * @return List<String> 模板名称列表
     */
    public static synchronized List<String> getList() {
        List<String> names = new ArrayList<>();
        ArrayNode templates = loadTemplatesNode();

        if (templates != null) {
            for (JsonNode node : templates) {
                if (node.has("name")) {
                    names.add(node.get("name").asText());
                }
            }
        }
        return names;
    }

    /**
     * 根据 name 获取完整的 template 数据
     *
     * @param name 模板名称
     * @return List<String> 顺序为 [method, path, body, headers]。如果未找到则返回 null。
     */
    public static synchronized List<String> get(String name) {
        ArrayNode templates = loadTemplatesNode();
        if (templates == null) return null;

        for (JsonNode node : templates) {
            if (node.has("name") && node.get("name").asText().equals(name)) {
                List<String> result = new ArrayList<>();
                result.add(getText(node, "method"));
                result.add(getText(node, "path"));
                result.add(getText(node, "body"));
                result.add(getText(node, "headers"));
                return result;
            }
        }
        return null;
    }

    /**
     * 保存模板配置 (Create or Replace)
     * 直接操作 TOML 结构，不使用 POJO
     */
    public static synchronized void save(String name, String method, String path, String body, String headers) {
        File file = getFile();
        ObjectNode root;
        ArrayNode templates;

        // 1. 读取或初始化根节点
        try {
            if (file.exists()) {
                root = (ObjectNode) mapper.readTree(file);
                JsonNode tNode = root.get("templates");
                if (tNode != null && tNode.isArray()) {
                    templates = (ArrayNode) tNode;
                } else {
                    templates = root.putArray("templates");
                }
            } else {
                root = mapper.createObjectNode();
                templates = root.putArray("templates");
            }
        } catch (IOException e) {
            // 文件损坏或无法解析时，重置为空结构
            root = mapper.createObjectNode();
            templates = root.putArray("templates");
        }

        // 2. 移除已存在的同名配置
        Iterator<JsonNode> iterator = templates.iterator();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            if (node.has("name") && node.get("name").asText().equals(name)) {
                iterator.remove();
                break;
            }
        }

        // 3. 追加新配置
        ObjectNode newNode = templates.addObject();
        newNode.put("name", name);
        newNode.put("method", method);
        newNode.put("path", path);
        newNode.put("body", body);
        newNode.put("headers", headers);

        // 4. 写回磁盘
        try {
            mapper.writeValue(file, root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save http.toml", e);
        }
    }

    // --- 内部私有辅助方法 ---

    /**
     * 统一获取目标文件路径，依赖 BasicConfig 已加载
     */
    private static File getFile() {
        String dir = BasicConfig.getConfigDir();
        if (dir == null) {
            // 防御性编程：如果 BasicConfig 还没初始化，尝试触发加载
            BasicConfig.load();
            dir = BasicConfig.getConfigDir();
        }
        return Path.of(dir, "http.toml").toFile();
    }

    private static ArrayNode loadTemplatesNode() {
        File file = getFile();
        if (!file.exists()) return null;

        try {
            JsonNode root = mapper.readTree(file);
            JsonNode templates = root.get("templates");
            if (templates != null && templates.isArray()) {
                return (ArrayNode) templates;
            }
        } catch (IOException e) {
            System.err.println("[TemplateConfig] Warning: Failed to parse http.toml: " + e.getMessage());
        }
        return null;
    }

    private static String getText(JsonNode node, String key) {
        return node.has(key) ? node.get(key).asText() : "";
    }
}