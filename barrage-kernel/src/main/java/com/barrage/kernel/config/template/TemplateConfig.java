package com.barrage.kernel.config.template;

import com.barrage.kernel.config.basic.BasicConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Barrage HTTP 请求模板配置管理器。
 * <p>
 * 该类负责管理存储在 {@code http.toml} 文件中的 HTTP 请求模板数据。
 * 它提供了一套基于 Key-Value 的持久化存储机制，允许用户定义和保存复杂的 HTTP 请求结构
 * （包含 Method, Path, Body, Headers），并在运行时按需加载。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>直接树操作 (Direct Tree Manipulation)：</b> 不使用 POJO 映射，而是直接操作 Jackson 的 {@link JsonNode} 和 {@link ObjectNode}。
 * 这种方式在处理 TOML 这种结构松散的配置文件时更加灵活，能够容忍未知字段。</li>
 * <li><b>自动初始化：</b> 如果配置文件不存在，{@code save} 方法会自动创建文件结构。</li>
 * <li><b>动态依赖：</b> 文件路径动态解析自 {@link BasicConfig#getConfigDir()}，支持多环境配置目录切换。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 所有公开的读写方法 ({@code get}, {@code save}, {@code getList}) 均由 {@code synchronized} 关键字修饰，
 * 确保在并发环境下的文件读写原子性。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class TemplateConfig {

    private static final ObjectMapper mapper = new TomlMapper();

    /**
     * 获取所有可用模板的名称列表。
     * <p>
     * 扫描 {@code http.toml} 中的 {@code [[templates]]} 数组，提取每个条目的 {@code name} 字段。
     *
     * @return 包含所有模板名称的列表；如果文件不存在或解析失败，返回空列表。
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
     * 获取指定模板的完整配置详情。
     * <p>
     * 根据模板名称查找对应的配置块，并按固定顺序返回核心 HTTP 参数。
     *
     * @param name 目标模板名称 (Case-sensitive)
     * @return 一个包含 4 个元素的字符串列表，顺序为：{@code [method, path, body, headers]}。
     * 如果未找到指定名称的模板，返回 {@code null}。
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
     * 新增或更新模板配置 (Upsert)。
     * <p>
     * 该操作是原子性的：
     * <ol>
     * <li>读取并解析现有的 {@code http.toml} 文件树。</li>
     * <li>若存在同名模板，先将其从数组中移除 (Replace)。</li>
     * <li>在数组末尾追加新的配置节点。</li>
     * <li>将整个树结构写回磁盘。</li>
     * </ol>
     * 如果文件不存在，会自动创建新的根节点结构。
     *
     * @param name    模板名称 (唯一索引)
     * @param method  HTTP 方法 (GET, POST 等)
     * @param path    请求路径 (如 /api/v1/test)
     * @param body    请求体 (Payload)
     * @param headers 请求头 (通常为 JSON 格式字符串或多行文本)
     * @throws RuntimeException 如果文件写入失败
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
        writeRoot(file, root);
    }


    /**
     * 批量替换所有模板（刷新语义：覆盖 http.toml 中的全部模板）。
     * <p>
     * 用于前端保存配置时将所有请求模板一次性同步到磁盘。
     *
     * @param templateList 模板列表，每项包含 name/method/path/body/headers
     * @throws RuntimeException 如果文件写入失败
     */
    public static synchronized void saveAll(List<Map<String, String>> templateList) {
        File file = getFile();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode templates = root.putArray("templates");

        for (Map<String, String> t : templateList) {
            ObjectNode node = templates.addObject();
            node.put("name",    t.getOrDefault("name",    ""));
            node.put("method",  t.getOrDefault("method",  "GET"));
            node.put("path",    t.getOrDefault("path",    "/"));
            node.put("body",    t.getOrDefault("body",    ""));
            node.put("headers", t.getOrDefault("headers", ""));
        }

        writeRoot(file, root);
    }
    // --- 内部私有辅助方法 ---

    /**
     * 统一获取目标文件路径，依赖 BasicConfig 已加载。
     * <p>
     * 包含防御性逻辑：如果全局配置尚未初始化，会尝试触发一次加载。
     */
    private static File getFile() {
        String dir;
        try {
            dir = BasicConfig.getConfigDir();
        } catch (IllegalStateException e) {
            // 防御性编程：如果 BasicConfig 还没初始化，尝试触发加载
            BasicConfig.load();
            dir = BasicConfig.getConfigDir();
        }
        return Path.of(dir, "http.toml").toFile();
    }

    /**
     * 统一写回 TOML，并在失败时给出可定位的路径和权限信息。
     */
    private static void writeRoot(File file, ObjectNode root) {
        try {
            Path target = file.toPath();
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(file, root);
        } catch (IOException e) {
            String abs = file.getAbsolutePath();
            String parentWritable = "unknown";
            String fileWritable = "unknown";
            try {
                Path target = file.toPath();
                Path parent = target.getParent();
                if (parent != null) parentWritable = String.valueOf(Files.isWritable(parent));
                if (Files.exists(target)) fileWritable = String.valueOf(Files.isWritable(target));
            } catch (Exception ignore) {
                // best effort diagnostics
            }
            throw new RuntimeException(
                    "Failed to save http.toml at: " + abs +
                            " (parent writable=" + parentWritable +
                            ", file writable=" + fileWritable + ")",
                    e
            );
        }
    }

    /**
     * 安全加载模板数组节点。
     * @return templates 数组节点，如果文件不存在或解析失败则返回 null
     */
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
