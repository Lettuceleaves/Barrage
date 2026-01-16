package com.barrage.kernel.config.basic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Barrage Kernel 的核心统一配置中心 (Unified Configuration Center)。
 * <p>
 * 该类作为全局单例（静态类），负责管理应用程序的所有运行时参数。
 * 它实现了配置文件的双向同步：既负责在启动时从磁盘 ({@code config.toml}) 加载参数到内存，
 * 也支持在运行时将内存中的修改回写持久化到磁盘。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>两级引导机制：</b> 首先读取 {@code path.toml} 定位配置目录，然后加载实际的 {@code config.toml}，
 * 支持灵活的部署结构。</li>
 * <li><b>严格初始化校验 (Strict Validation)：</b> 在 {@code finishInitialization} 中强制检查所有必填字段，
 * 防止因配置缺项导致的运行时空指针异常 (NPE)。对于 {@code QUEUE_DEPTH} 等敏感参数，
 * 还会校验其是否符合物理限制（如必须为 2 的幂）。</li>
 * <li><b>内存屏障保护：</b> 所有 Getter 方法均受 {@code checkReady()} 保护，
 * 确保在配置未完全加载就绪前，任何访问尝试都会抛出致命错误，防止脏读。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>部分线程安全。</b>
 * 初始化过程 ({@code load}, {@code finishInitialization}) 是同步的。
 * 运行时修改 (Setters) 和读取 (Getters) 操作直接读写静态字段。
 * 在 CLI 的单线程交互模型下是安全的，但在多线程并发修改配置的极端场景下可能存在可见性问题（尽管实际业务中极少发生）。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
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

    /**
     * 从磁盘加载并初始化内核配置。
     * <p>
     * 执行流程：
     * <ol>
     * <li>读取根目录下的 {@code path.toml}，解析 {@code config_dir} 路径。</li>
     * <li>在指定目录下寻找 {@code config.toml}。</li>
     * <li>使用 Jackson TOML Mapper 解析配置文件，并将值注入到静态字段中。</li>
     * <li>执行 {@code finishInitialization} 进行完整性校验。</li>
     * </ol>
     * 如果任一步骤失败（如文件缺失、格式错误、字段遗漏），程序将打印错误日志并直接退出 (System.exit)。
     */
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

    /**
     * 将当前内存中的配置状态持久化到磁盘。
     * <p>
     * 该方法会重写 {@code config.toml} 文件，格式化输出当前的参数值。
     * 用于保存用户在 CLI 界面中所做的修改（如更换模板、调整线程数等）。
     *
     * @throws RuntimeException 如果写入文件失败
     */
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

    /**
     * 完成初始化并执行完整性校验。
     * <p>
     * 检查所有必须的配置字段是否已赋值。如果发现任何 {@code null} 值，抛出异常。
     * 此方法是 {@link #initialized} 标志位置位的唯一入口。
     */
    private static synchronized void finishInitialization() {
        if (ip == null || port == null || serverThreads == null || clientThreads == null ||
                connsPerClient == null || step == null || inFlight == null || queueDepth == null ||
                batchSize == null || readSz == null || activeTemplateName == null) {
            throw new IllegalStateException("[FATAL] BasicConfig: Missing required fields during initialization!");
        }
        initialized = true;
    }

    /**
     * 状态屏障。
     * <p>
     * 确保配置已加载。如果未初始化即访问 Getter，抛出致命错误。
     */
    private static void checkReady() {
        if (!initialized) throw new IllegalStateException("[FATAL] BasicConfig accessed before initialized!");
    }

    // --- Set 接口 ---

    public static void setIP(String val) { ip = val; }
    public static void setPORT(int val) { port = val; }
    public static void setSERVER_THREADS(int val) { serverThreads = val; }
    public static void setCLIENT_THREADS(int val) { clientThreads = val; }
    public static void setCONNS_PER_CLIENT(int val) { connsPerClient = val; }

    /** 设置 QPS 递增步长 (Step)。 */
    public static void setSTEP(int val) { step = val; }
    public static void setIN_FLIGHT(int val) { inFlight = val; }

    /**
     * 设置 io_uring 提交/完成队列深度。
     * @param val 深度值，必须为 > 0 的 2 的幂 (如 4096, 8192)。
     * @throws IllegalArgumentException 如果参数校验失败
     */
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

    /** 获取 QPS 递增步长 (用于 Ramping 策略)。 */
    public static int getSTEP() { checkReady(); return step; }
    public static int getIN_FLIGHT() { checkReady(); return inFlight; }
    public static int getQUEUE_DEPTH() { checkReady(); return queueDepth; }
    public static int getBATCH_SIZE() { checkReady(); return batchSize; }
    public static int getREAD_SZ() { checkReady(); return readSz; }
    public static String getACTIVE_TEMPLATE_NAME() { checkReady(); return activeTemplateName; }

    public static String getConfigDir() { checkReady(); return configDir; }

    /**
     * 便捷方法：同时更新 IP 和端口。
     * @param newIp   新目标 IP
     * @param newPort 新目标端口
     */
    public static synchronized void updateEndpoint(String newIp, int newPort) {
        setIP(newIp);
        setPORT(newPort);
    }
}