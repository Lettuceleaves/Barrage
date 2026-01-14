package com.barrage.protocol.datasource;

import com.barrage.kernel.config.BasicConfig;
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
 * 基于控制台交互的 HTTP 数据源实现。
 * <p>
 * 该类提供了一个 CLI 向导模式，允许用户在启动时通过命令行动态输入 HTTP 请求的各个组件
 * （如 Method, Path, User-Agent 等）。
 *
 * <h2>功能特性：</h2>
 * <ul>
 * <li><b>交互式构建：</b> 根据 {@link ProtocolRule} 定义的必要组件，逐项提示用户输入。</li>
 * <li><b>智能默认值：</b> 自动读取 {@link BasicConfig} 中的 IP、端口配置作为默认选项，
 * 用户只需按回车即可使用当前系统配置。</li>
 * <li><b>内存桥接：</b> 虽然输入过程涉及 Java String 和 Heap 对象，但在 {@link #load} 完成后，
 * 数据会被立即“固化”到 {@link MemoryArena} 堆外内存中，供引擎进行零拷贝发送。</li>
 * </ul>
 *
 * <h2>适用场景：</h2>
 * 仅用于开发调试或临时手动验证特定请求格式。生产环境压测请使用 {@link FileDataSource}。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 */
public class ConsoleDataSource extends DataSource {

    private final MemoryArena pool;
    private final ProtocolRule rule;

    /**
     * 构造控制台数据源。
     *
     * @param pool 用于存储最终生成的 HTTP 请求报文的内存池
     * @param rule 协议构建规则（策略模式），定义了如何将分散的输入组装成最终报文
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Internal components storage for functionality"
    )
    public ConsoleDataSource(MemoryArena pool, ProtocolRule rule) {
        this.pool = pool;
        this.rule = rule;
    }

    /**
     * 执行交互式加载流程。
     * <p>
     * 流程：
     * <ol>
     * <li>启动控制台向导，采集用户输入。</li>
     * <li>调用 {@link ProtocolRule#build} 将输入组装成符合 HTTP 协议的字符串。</li>
     * <li>将字符串编码为 ASCII 字节流（符合 HTTP 协议标准）。</li>
     * <li><b>关键步骤：</b>从 {@link MemoryArena} 分配槽位，并将字节流写入堆外内存 {@link MemorySegment}。</li>
     * </ol>
     *
     * @param arena 作用域 Arena（本方法中暂未使用，依赖内部注入的 pool）
     * @return 包含完整 HTTP 报文的堆外内存段
     * @throws IOException 如果输入流被意外关闭
     */
    @Override
    public MemorySegment load(Arena arena) throws IOException {
        // 1. 调用内部的装填逻辑 (Filler 逻辑现在内聚在这里)
        Map<String, Object> components = fillFromConsole();

        // 2. 委托协议规则进行拼装
        String finalContent = rule.build(components);

        // 3. 执行 Zero-GC 内存装载
        // 注意：此处产生的 byte[] 是临时对象，加载完成后即成为垃圾，
        // 但这是在压测开始前的 Setup 阶段，不会影响运行时的 GC 表现。
        byte[] bytes = finalContent.getBytes(StandardCharsets.US_ASCII);
        int index = pool.allocate();
        MemorySegment buffer = pool.getBuffer(index);

        long len = Math.min(bytes.length, buffer.byteSize());
        buffer.asSlice(0, len).copyFrom(MemorySegment.ofArray(bytes));

        System.out.println(">>> HTTP Message built and loaded into MemoryArena.");
        return buffer;
    }

    /**
     * 核心装填逻辑：根据 Rule 定义的元数据进行控制台提问。
     * <p>
     * 遍历 {@link ProtocolRule#requiredComponents()} 返回的所有字段，
     * 结合 {@link BasicConfig} 提供智能默认值。
     *
     * @return 包含组件名和用户输入值的映射表
     */
    private Map<String, Object> fillFromConsole() {
        // 显式指定 UTF-8 以避免不同 OS 终端编码差异
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
     * 根据组件名称获取建议的默认值。
     * <p>
     * 实现了组件名到 {@link BasicConfig} 静态配置的动态映射。
     *
     * @param component 组件名称（如 "Host", "Port"）
     * @return 默认值字符串，如果无对应默认值则返回空串
     */
    private String getDefaultValueFor(String component) {
        return switch (component) {
            case "Method" -> "GET";
            case "Host"   -> BasicConfig.getIP();
            case "Port"   -> String.valueOf(BasicConfig.getPORT());
            case "Path"   -> "/";
            case "Body"   -> ""; // Body 默认留空
            default       -> "";
        };
    }

    /**
     * 获取数据源大小。
     *
     * @return 返回 -1，表示交互式输入无法在加载前预知大小。
     */
    @Override
    public long size() {
        return -1; // 控制台输入大小在输入前未知
    }
}