package com.barrage.protocol;

import java.util.List;
import java.util.Map;

/**
 * 协议构建规则策略接口。
 * <p>
 * 该接口定义了应用层协议（如 HTTP, Redis, Memcached）的结构描述与序列化逻辑。
 * 它实现了<b>数据采集</b>与<b>报文组装</b>的解耦，使得 {@code ConsoleDataSource} 等通用组件
 * 能够根据不同的协议规则动态调整交互逻辑。
 *
 * <h2>核心职责：</h2>
 * <ul>
 * <li><b>Schema 定义：</b> 声明该协议由哪些必要组件构成（如 "Method", "Key", "Value"）。</li>
 * <li><b>序列化：</b> 将离散的组件值拼装成符合协议规范（如 RFC 标准）的最终字符串。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/12
 * @see com.barrage.protocol.HTTP.HttpRule
 */
public interface ProtocolRule {

    /**
     * 定义该协议所需的必要组件清单（Schema）。
     * <p>
     * 交互式数据源（如 {@code ConsoleDataSource}）会调用此方法，
     * 依次向用户询问列表中定义的每一个字段。
     *
     * @return 字段名称列表（有序），例如 {@code ["Method", "Host", "URI"]}。
     */
    List<String> requiredComponents();

    /**
     * 执行协议序列化构建。
     * <p>
     * 将用户输入或配置文件中读取的离散数据，按照协议规范拼接成最终的报文 payload。
     * 实现类在此处应当处理默认值填充、格式校验、编码转换（如 Content-Length 计算）等逻辑。
     *
     * @param components 包含组件名和对应值的映射表，键名必须与 {@link #requiredComponents()} 对应。
     * @return 符合传输协议规范的原始报文字符串。
     * @throws IllegalArgumentException 如果缺少必要组件或组件格式校验失败。
     */
    String build(Map<String, Object> components);
}