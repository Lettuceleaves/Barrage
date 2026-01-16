package com.barrage.protocol.datasource;

import com.barrage.protocol.HTTP.HttpTemplate;

/**
 * HTTP 数据源加载策略接口 (Strategy Interface)。
 * <p>
 * 该接口定义了从不同来源（如本地文件、控制台交互、远程配置中心等）加载 {@link HttpTemplate} 的统一契约。
 * 它是 Barrage 启动流程中“配置加载”阶段的核心抽象，允许上层业务逻辑在不感知具体实现的情况下，
 * 透明地获取组装好的 HTTP 请求模板。
 *
 * <h2>设计模式：</h2>
 * <ul>
 * <li><b>策略模式 (Strategy Pattern)：</b> 不同的实现类（如 {@link ConsoleDataSource}）代表不同的加载策略，
 * 可以在运行时根据 {@code DataSourceType} 动态切换。</li>
 * <li><b>统一适配：</b> 无论底层数据是 JSON、TOML 还是用户手动输入，最终都必须适配为标准的 {@link HttpTemplate} 对象。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>实现依赖 (Implementation Dependent)。</b>
 * 接口本身不保证线程安全。通常情况下，数据加载发生在引擎启动前的初始化阶段 (Main Thread)，
 * 因此不需要复杂的同步控制。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public interface DataSource {

    /**
     * 执行数据加载逻辑。
     * <p>
     * 根据具体的实现策略，解析传入的参数并构建 HTTP 请求模板。
     *
     * @param param 上下文参数。其含义取决于具体的实现类：
     * <ul>
     * <li>对于 <b>File Source</b>：通常是模板名称 (Template Name) 或文件路径。</li>
     * <li>对于 <b>Console Source</b>：通常被忽略 (Ignored) 或作为默认提示值。</li>
     * </ul>
     * @return 组装完成且经过基础校验的 {@link HttpTemplate} 对象
     * @throws RuntimeException 如果数据加载失败、解析错误或参数校验未通过
     */
    HttpTemplate load(String param);
}