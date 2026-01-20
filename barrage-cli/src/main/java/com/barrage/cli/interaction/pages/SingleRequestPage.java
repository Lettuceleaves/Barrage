package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;
import com.barrage.engine.StandardEngine;
import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.protocol.HTTP.HttpTemplate;
import com.barrage.protocol.datasource.ConsoleDataSource;

/**
 * 单点测试交互页面。
 * <p>
 * 该页面用于引导用户进行一次性的接口连通性测试。
 * 它复用了 {@link ConsoleDataSource} 的输入逻辑，并调用 {@link StandardEngine} 执行实际请求。
 */
public class SingleRequestPage implements Ansi {

    public static void open(Terminal t) {
        t.clear();
        t.section(CYAN + "Single Request Mode (Connectivity Check)" + RESET);
        t.info("This mode sends a SINGLE synchronous request to verify the target.");
        t.info("Resources will be allocated and released immediately.");

        try {
            // 1. 复用现有的控制台输入逻辑获取模板
            // ConsoleDataSource 会引导用户输入 IP, Port, Method, Path, Body 等
            // 并自动同步到 BasicConfig
            HttpTemplate template = new ConsoleDataSource().load(null);

            // 2. 询问超时时间
            int timeout = t.readInt("Timeout (ms)", 3000);

            t.line();
            t.info(">>> Sending request...");

            // 3. 执行请求 (捕获所有耗时和异常)
            long start = System.currentTimeMillis();
            String response = StandardEngine.execute(
                    BasicConfig.getIP(),
                    BasicConfig.getPORT(),
                    template,
                    timeout
            );
            long cost = System.currentTimeMillis() - start;

            // 4. 展示结果
            t.success("Request Completed in " + cost + "ms");
            t.section("Response Body:");
            System.out.println(response); // 直接打印原始内容

        } catch (Exception e) {
            t.error("\n[Request Failed] " + e.getMessage());
            // 如果是详细的异常，可以考虑打印堆栈，或者仅显示 Message
            if (e.getCause() != null) {
                t.warn("Cause: " + e.getCause().getMessage());
            }
        }

        t.line();
        t.pause(); // 等待用户按回车返回主菜单
    }
}