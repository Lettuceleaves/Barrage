package com.barrage.protocol.datasource;


/**
 * HTTP 请求数据源类型的枚举定义。
 * <p>
 * 它决定了 {@code LaunchContext} 最终会实例化哪种具体的 {@link DataSource} 策略实现类
 * 来构建 HTTP 请求模板。
 *
 * <h2>类型说明：</h2>
 * <ul>
 * <li><b>CONSOLE:</b> 交互式模式。适用于临时性调试或简单测试，用户通过终端向导手动输入 Host、Method、Body 等参数。</li>
 * <li><b>FILE:</b> 模板文件模式。适用于标准化、可复现的压测场景，直接从 {@code http.toml} 配置文件中加载预定义的请求模板。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public enum DataSourceType {

    /**
     * 交互式控制台输入 (Interactive Console Input)。
     * <p>
     * 对应 {@link ConsoleDataSource} 策略。
     */
    CONSOLE,

    /**
     * 本地文件加载 (Static File / Template)。
     * <p>
     * 对应基于 {@code TemplateConfig} 的加载策略。
     */
    FILE;

}