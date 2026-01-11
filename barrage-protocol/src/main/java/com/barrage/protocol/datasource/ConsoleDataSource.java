package com.barrage.protocol.datasource;

import com.barrage.kernel.config.GlobalConfig;
import com.barrage.kernel.memory.MemoryArena;
import com.barrage.protocol.ProtocolRule;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 专门负责从控制台交互式装填协议数据的数据源。
 */
public class ConsoleDataSource extends DataSource {

    private final MemoryArena pool;
    private final ProtocolRule rule;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConsoleDataSource(MemoryArena pool, ProtocolRule rule) {
        this.pool = pool;
        this.rule = rule;
    }

    @Override
    public MemorySegment load(Arena arena) throws IOException {
        // 1. 调用内部的装填逻辑 (Filler 逻辑现在内聚在这里)
        Map<String, Object> components = fillFromConsole();

        // 2. 委托协议规则进行拼装
        String finalContent = rule.build(components);

        // 3. 执行 Zero-GC 内存装载
        byte[] bytes = finalContent.getBytes(StandardCharsets.US_ASCII);
        int index = pool.allocate();
        MemorySegment buffer = pool.getBuffer(index);

        long len = Math.min(bytes.length, buffer.byteSize());
        buffer.asSlice(0, len).copyFrom(MemorySegment.ofArray(bytes));

        System.out.println(">>> HTTP Message built and loaded into MemoryArena.");
        return buffer;
    }

    /**
     * 核心装填逻辑：根据 Rule 定义的元数据进行控制台提问
     */
    private Map<String, Object> fillFromConsole() {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        Map<String, Object> data = new HashMap<>();

        System.out.println("\n" + "=".repeat(45));
        System.out.println("   HTTP REQUEST BUILDER (Press ENTER for Default)");
        System.out.println("=".repeat(45));

        for (String component : rule.requiredComponents()) {
            // 获取当前 GlobalConfig 中的默认值用于展示
            String defaultValue = getDefaultValueFor(component);

            System.out.print(String.format("Enter %-7s [%s]: ", component, defaultValue));
            String input = scanner.nextLine().trim();

            // 如果用户直接回车（输入为空），则使用默认值
            if (input.isEmpty()) {
                data.put(component, defaultValue);
            } else {
                data.put(component, input);
            }
        }
        return data;
    }

    /**
     * 根据组件名称映射 GlobalConfig 中的静态字段
     */
    private String getDefaultValueFor(String component) {
        return switch (component) {
            case "Method" -> "GET";
            case "Host"   -> GlobalConfig.getIP();
            case "Port"   -> String.valueOf(GlobalConfig.getPORT());
            case "Path"   -> "/";
            case "Body"   -> ""; // Body 默认留空
            default       -> "";
        };
    }

    @Override
    public long size() {
        return -1; // 控制台输入大小在输入前未知
    }
}