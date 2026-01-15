package com.barrage.kernel.config.basic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Barrage Kernel 严格配置中心 (Unified)
 */
@SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Configuration must be injected at runtime")
public class BasicConfig {

    // --- 状态与工具 ---
    private static boolean initialized = false;
    private static final TomlMapper mapper = new TomlMapper();
    private static String configDir;

    // --- 配置字段 ---
    private static String ip;
    private static Integer port;
    private static Integer serverThreads;
    private static Integer clientThreads;
    private static Integer connsPerClient;
    private static Integer step; // 已添加字段
    private static Integer inFlight;
    private static Integer queueDepth;
    private static Integer batchSize;
    private static Integer readSz;
    private static String activeTemplateName;

    // =================================================================================
    // Part 1: 加载与持久化逻辑
    // =================================================================================

    public static void load() {
        try {
            File bootstrap = new File("path.toml");
            if (!bootstrap.exists()) {
                throw new RuntimeException("Bootstrap file 'path.toml' is missing!");
            }

            JsonNode bootstrapNode = mapper.readTree(bootstrap);
            JsonNode dirNode = bootstrapNode.has("bootstrap") ? bootstrapNode.get("bootstrap") : bootstrapNode.get("storage");
            configDir = getRequired(dirNode, "config_dir").asText();

            Path configPath = Path.of(configDir, "config.toml");
            File configFile = configPath.toFile();
            if (!configFile.exists()) {
                throw new RuntimeException("config.toml not found at: " + configPath);
            }

            JsonNode root = mapper.readTree(configFile);

            // 3. 注入 Network 参数
            JsonNode net = root.path("network");
            setIP(getRequired(net, "ip").asText());
            setPORT(getRequired(net, "port").asInt());

            // 4. 注入 Engine 参数
            JsonNode eng = root.path("engine");
            setSERVER_THREADS(getRequired(eng, "server_threads").asInt());
            setCLIENT_THREADS(getRequired(eng, "client_threads").asInt());
            setCONNS_PER_CLIENT(getRequired(eng, "conns_per_client").asInt());
            setSTEP(getRequired(eng, "step").asInt()); // 补充加载逻辑

            // 5. 注入 Performance 参数
            JsonNode perf = root.path("performance");
            setIN_FLIGHT(getRequired(perf, "in_flight").asInt());
            setQUEUE_DEPTH(getRequired(perf, "queue_depth").asInt());
            setBATCH_SIZE(getRequired(perf, "batch_size").asInt());
            setREAD_SZ(getRequired(perf, "read_sz").asInt());

            // 6. 注入 Template 参数
            JsonNode tpl = root.path("template");
            setACTIVE_TEMPLATE_NAME(getRequired(tpl, "active").asText());

            finishInitialization();
            System.out.println(">>> [Config] Kernel parameters strictly initialized from: " + configPath);

        } catch (Exception e) {
            System.err.println(">>> [Config] FATAL: Configuration check failed! Reason: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void save() {
        checkReady();
        try {
            if (configDir == null) load();
            File configFile = Path.of(configDir, "config.toml").toFile();

            try (PrintWriter writer = new PrintWriter(new FileWriter(configFile))) {
                writer.println("[network]");
                writer.printf("ip = \"%s\"%n", getIP());
                writer.printf("port = %d%n", getPORT());
                writer.println();

                writer.println("[engine]");
                writer.printf("server_threads = %d%n", getSERVER_THREADS());
                writer.printf("client_threads = %d%n", getCLIENT_THREADS());
                writer.printf("conns_per_client = %d%n", getCONNS_PER_CLIENT());
                writer.printf("step = %d%n", getSTEP()); // 补充持久化逻辑
                writer.println();

                writer.println("[performance]");
                writer.printf("in_flight = %d%n", getIN_FLIGHT());
                writer.printf("queue_depth = %d%n", getQUEUE_DEPTH());
                writer.printf("batch_size = %d%n", getBATCH_SIZE());
                writer.printf("read_sz = %d%n", getREAD_SZ());
                writer.println();

                writer.println("[template]");
                writer.printf("active = \"%s\"%n", getACTIVE_TEMPLATE_NAME());
            }
        } catch (Exception e) {
            throw new RuntimeException("Persistence failed: " + e.getMessage());
        }
    }

    private static JsonNode getRequired(JsonNode node, String key) {
        if (node.isMissingNode() || !node.has(key)) {
            throw new IllegalArgumentException("Missing required TOML key: '" + key + "'");
        }
        return node.get(key);
    }

    // =================================================================================
    // Part 2: 状态管理与 Setter/Getter
    // =================================================================================

    private static synchronized void finishInitialization() {
        if (ip == null || port == null || serverThreads == null || clientThreads == null ||
                connsPerClient == null || step == null || inFlight == null || queueDepth == null ||
                batchSize == null || readSz == null || activeTemplateName == null) {
            throw new IllegalStateException("[FATAL] BasicConfig: Missing required fields during initialization!");
        }
        initialized = true;
    }

    private static void checkReady() {
        if (!initialized) throw new IllegalStateException("[FATAL] BasicConfig accessed before initialized!");
    }

    // --- Set 接口 ---

    public static void setIP(String val) { ip = val; }
    public static void setPORT(int val) { port = val; }
    public static void setSERVER_THREADS(int val) { serverThreads = val; }
    public static void setCLIENT_THREADS(int val) { clientThreads = val; }
    public static void setCONNS_PER_CLIENT(int val) { connsPerClient = val; }
    public static void setSTEP(int val) { step = val; } // 补充 Setter
    public static void setIN_FLIGHT(int val) { inFlight = val; }

    public static void setQUEUE_DEPTH(int val) {
        if (val <= 0 || (val & (val - 1)) != 0) {
            throw new IllegalArgumentException("QUEUE_DEPTH must be a power of 2 and > 0");
        }
        queueDepth = val;
    }

    public static void setBATCH_SIZE(int val) { batchSize = val; }
    public static void setREAD_SZ(int val) { readSz = val; }
    public static void setACTIVE_TEMPLATE_NAME(String val) { activeTemplateName = val; }

    // --- Get 接口 ---

    public static String getIP() { checkReady(); return ip; }
    public static int getPORT() { checkReady(); return port; }
    public static int getSERVER_THREADS() { checkReady(); return serverThreads; }
    public static int getCLIENT_THREADS() { checkReady(); return clientThreads; }
    public static int getCONNS_PER_CLIENT() { checkReady(); return connsPerClient; }
    public static int getSTEP() { checkReady(); return step; } // 补充 Getter
    public static int getIN_FLIGHT() { checkReady(); return inFlight; }
    public static int getQUEUE_DEPTH() { checkReady(); return queueDepth; }
    public static int getBATCH_SIZE() { checkReady(); return batchSize; }
    public static int getREAD_SZ() { checkReady(); return readSz; }
    public static String getACTIVE_TEMPLATE_NAME() { checkReady(); return activeTemplateName; }

    public static String getConfigDir() { checkReady(); return configDir; }

    public static synchronized void updateEndpoint(String newIp, int newPort) {
        setIP(newIp);
        setPORT(newPort);
    }
}