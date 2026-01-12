package com.barrage.protocol;

import java.util.Map;

/**
 * 交互式数据填充策略接口。
 * <p>
 * 该接口定义了“获取协议参数”的抽象行为，屏蔽了底层具体的输入介质。
 * 通过实现此接口，可以将数据采集逻辑（如控制台输入、GUI 表单、Web 钩子）
 * 与协议构建逻辑（如 HTTP 报文拼装）完全解耦。
 *
 * <h2>设计意图：</h2>
 * 允许 {@code DataSource} 在不知道具体输入源的情况下，
 * 动态地根据 {@link ProtocolRule} 的要求采集数据。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see ProtocolRule
 */
public interface InteractiveFiller {

    /**
     * 根据协议规则执行数据填充。
     * <p>
     * 实现类应当遍历 {@link ProtocolRule#requiredComponents()} 获取所需字段列表，
     * 并通过特定的交互方式（如打印 Prompt、弹出对话框）向用户索取对应的值。
     *
     * @param rule 协议规则元数据，定义了需要采集哪些字段（如 "Host", "Port"）。
     * @return 包含用户输入值的键值对映射 (Component Name -> User Input)。
     */
    Map<String, Object> fill(ProtocolRule rule);
}