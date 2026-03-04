package com.barrage.api.service;

import com.barrage.engine.SimulationEngine;
import com.barrage.engine.simulate.ExecutionGraph;
import com.barrage.engine.simulate.config.GraphLoader;
import com.barrage.engine.simulate.config.GraphLoader.ScenarioConfigDTO;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模拟测试引擎生命周期管理服务（单例）。
 * <p>
 * 负责管理 {@link SimulationEngine} 的启动与停止，
 * 并封装 {@link GraphLoader} 的场景文件读写操作。
 * 是 API Handler 访问模拟引擎的唯一入口。
 */
public final class SimulateService {

    /** 配置文件根目录（与 CLI 保持一致） */
    private static final String CONFIG_ROOT = "./config";

    /** 默认场景文件名 */
    private static final String DEFAULT_SCENARIO = "scenario.yaml";

    /** 全局单例 */
    private static final SimulateService INSTANCE = new SimulateService();

    private SimulateService() {}

    public static SimulateService getInstance() { return INSTANCE; }

    // =============================================
    // 运行状态
    // =============================================
    private volatile SimulationEngine engine;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String currentScenario = DEFAULT_SCENARIO;
    private volatile long startTimeMs = 0;

    // =============================================
    // 场景文件管理
    // =============================================

    /**
     * 列出 config 目录下所有 YAML 场景文件名。
     */
    public java.util.List<String> listScenarios() {
        return GraphLoader.listScenarios(CONFIG_ROOT);
    }

    /**
     * 加载并返回指定场景文件的 DTO（用于前端回显）。
     *
     * @param fileName 文件名（如 "scenario.yaml"）
     * @return 场景配置 DTO
     * @throws RuntimeException 若文件不存在或解析失败
     */
    public ScenarioConfigDTO loadScenario(String fileName) {
        // 借用 GraphLoader 内部的 load 来验证可用性，再直接读 DTO
        // 这里直接用 Jackson 读 DTO，避免进行图验证
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper(
                            new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            java.io.File file = new java.io.File(CONFIG_ROOT, fileName);
            if (!file.exists()) throw new RuntimeException("场景文件不存在: " + fileName);
            return mapper.readValue(file, ScenarioConfigDTO.class);
        } catch (IOException e) {
            throw new RuntimeException("加载场景文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存（新建或覆盖）场景配置到 config 目录。
     *
     * @param fileName 文件名（如 "my_scene.yaml"），若不以 .yaml/.yml 结尾则自动追加 .yaml
     * @param dto      场景配置 DTO
     * @throws IOException 写入失败
     */
    public void saveScenario(String fileName, ScenarioConfigDTO dto) throws IOException {
        // 确保文件名有 .yaml 扩展名
        if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) {
            fileName = fileName + ".yaml";
        }
        GraphLoader.save(CONFIG_ROOT, fileName, dto);
    }

    // =============================================
    // 模拟测试生命周期
    // =============================================

    /**
     * 启动模拟测试引擎。
     *
     * @param scenarioFileName 场景文件名（null 则使用默认 scenario.yaml）
     * @throws IllegalStateException 若引擎已在运行
     * @throws IOException           若网络基础设施初始化失败
     */
    public synchronized void start(String scenarioFileName) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("模拟测试已在运行，请先调用 /api/simulate/stop");
        }

        String file = (scenarioFileName != null && !scenarioFileName.isBlank())
                ? scenarioFileName : DEFAULT_SCENARIO;

        // 加载执行图（同时做 validate）
        ExecutionGraph graph = GraphLoader.load(CONFIG_ROOT, file);

        engine = new SimulationEngine(graph);
        running.set(true);
        startTimeMs = System.currentTimeMillis();
        currentScenario = file;

        engine.start();
        System.out.println("[SimulateService] 模拟测试已启动，场景: " + file);
    }

    /**
     * 停止模拟测试引擎。
     */
    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        System.out.println("[SimulateService] 模拟测试已停止");
    }

    /** 是否正在运行 */
    public boolean isRunning() { return running.get(); }

    /**
     * 获取模拟测试运行状态快照。
     */
    public java.util.Map<String, Object> getStatus() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("running", running.get());
        m.put("scenario", currentScenario);
        if (running.get() && engine != null) {
            m.put("activeAgents",  engine.getActiveAgentCount());
            m.put("uptimeSeconds", (System.currentTimeMillis() - startTimeMs) / 1000);
        }
        return m;
    }
}
