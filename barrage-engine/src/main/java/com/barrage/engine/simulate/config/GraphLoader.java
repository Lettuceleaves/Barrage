package com.barrage.engine.simulate.config;

import com.barrage.engine.simulate.ExecutionGraph;
import com.barrage.engine.simulate.GraphUtils;
import com.barrage.engine.simulate.TransitionContext;
import com.barrage.engine.simulate.node.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行图加载器 (已修复 TransitionContext 构造问题)
 */
public class GraphLoader {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /**
     * 加载入口
     * @param configRootPath 配置文件根目录
     * @param fileName 文件名 (如 scenario.yaml)
     */
    public static ExecutionGraph load(String configRootPath, String fileName) {
        File configFile = new File(configRootPath, fileName);
        System.out.println("[GraphLoader] Loading config from: " + configFile.getAbsolutePath());

        try {
            // 1. YAML -> DTO
            ScenarioConfigDTO config = mapper.readValue(configFile, ScenarioConfigDTO.class);

            // 2. Build Graph
            ExecutionGraph graph = new ExecutionGraph(config.graphName);
            graph.setStartNodeId(config.startNodeId);

            if (config.nodes != null) {
                for (NodeDTO nodeDto : config.nodes) {
                    // id 作为 key, 转换后的 node 作为 value
                    graph.addNode(nodeDto.id, convert(nodeDto));
                }
            }

            // 3. Validate
            GraphUtils.validate(graph);

            return graph;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load scenario config", e);
        }
    }

    /**
     * 核心转换逻辑：DTO -> Runtime Node
     */
    private static GraphNode convert(NodeDTO dto) {
        String type = dto.type == null ? "UNKNOWN" : dto.type.toUpperCase();

        return switch (type) {
            case "START" -> new StartNode(
                    dto.name,
                    toTransList(dto.transition)
            );

            case "TERMINAL" -> new TerminalNode(
                    dto.name,
                    dto.resultTag,
                    dto.saveContext
            );

            case "HTTP", "ACTION_HTTP" -> new HttpNode(
                    dto.name,
                    toTransList(dto.transition),
                    dto.templateRef
            );

            case "CONDITION", "LOGIC_CONDITION" -> {
                byte[] expectedBytes = dto.expectedValue != null ?
                        dto.expectedValue.getBytes(StandardCharsets.UTF_8) : new byte[0];

                List<TransitionContext> list = new ArrayList<>();
                // 约定：Index 0=Match, Index 1=Mismatch
                list.add(toTransObj(dto.matchTransition));
                list.add(toTransObj(dto.mismatchTransition));

                yield new ConditionNode(
                        dto.name,
                        list,
                        expectedBytes
                );
            }

            case "CHANCE", "LOGIC_CHANCE" -> {
                List<Integer> weights = new ArrayList<>();
                List<TransitionContext> trans = new ArrayList<>();
                if (dto.branches != null) {
                    for (BranchDTO b : dto.branches) {
                        weights.add(b.weight);
                        trans.add(toTransObj(b.transition));
                    }
                }
                yield new ChanceNode(dto.name, trans, weights);
            }

            default -> throw new IllegalArgumentException("Unknown Node Type: " + type);
        };
    }

    // --- Helpers (Fixed) ---

    private static TransitionContext toTransObj(TransitionDTO t) {
        if (t == null) return null;
        // 修复：参数顺序 (next, mode, value)
        return new TransitionContext(t.next, t.mode, t.value);
    }

    private static List<TransitionContext> toTransList(TransitionDTO t) {
        if (t == null) return Collections.emptyList();
        // 修复：参数顺序 (next, mode, value)
        return List.of(new TransitionContext(t.next, t.mode, t.value));
    }

    // =======================================================
    // 内部 DTOs
    // =======================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenarioConfigDTO {
        public String graphName;
        public String startNodeId;
        public List<NodeDTO> nodes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeDTO {
        public String id;
        public String type;
        public String name;

        // HttpNode
        public String templateRef;

        // ConditionNode
        public String expectedValue;

        // TerminalNode
        public String resultTag;
        public boolean saveContext;

        // Transitions
        public TransitionDTO transition;
        public TransitionDTO matchTransition;
        public TransitionDTO mismatchTransition;
        public List<BranchDTO> branches;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransitionDTO {
        public String next;
        public String mode;
        public String value; // 修复：增加 value 字段接收 YAML 参数
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BranchDTO {
        public int weight;
        public TransitionDTO transition;
    }
}