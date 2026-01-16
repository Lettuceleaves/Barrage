package com.barrage.protocol.datasource;

import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.protocol.HTTP.HttpTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 基于控制台交互的 HTTP 请求构建器。
 * <p>
 * 该类实现了 {@link DataSource} 接口，用于在运行时通过终端向导 (Wizard)
 * 引导用户手动输入 HTTP 请求的各项参数（Host, Port, Method, Path, Body 等）。
 * 它主要用于临时性的调试或测试，无需预先编写 TOML 模板文件。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>智能默认值 (Smart Defaults)：</b> 自动读取 {@link BasicConfig} 中的全局 IP 和端口作为默认建议，
 * 用户直接回车即可使用默认值，提升操作效率。</li>
 * <li><b>动态头补充：</b> 当用户输入非空 Body 时，会自动提示设置 {@code Content-Type}（默认为 JSON），
 * 避免因缺失关键 Header 导致服务端解析失败。</li>
 * <li><b>即时校验：</b> 构建完成后立即调用 {@link HttpTemplate#check()} 进行格式验证，确保生成的请求合法。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 该类直接操作标准输入流 ({@code System.in})，且仅设计用于单线程的 CLI 初始化阶段。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class ConsoleDataSource implements DataSource {

    /**
     * 启动交互式构建流程。
     * <p>
     * 在控制台打印构建向导，逐步读取用户输入并组装 {@link HttpTemplate} 对象。
     * 流程如下：
     * <ol>
     * <li>确认目标 Host 和 Port (默认回显全局配置)。</li>
     * <li>输入 HTTP 方法 (默认为 GET) 和路径 (默认为 /)。</li>
     * <li>输入请求体 (Body)。若不为空，追加询问 Content-Type。</li>
     * <li>执行参数合法性校验。</li>
     * </ol>
     *
     * @param param 扩展参数，在此实现中被忽略 (Ignored)，因为数据源直接来自用户输入。
     * @return 构建完成且通过校验的 HTTP 模板对象。
     * @throws RuntimeException 如果用户输入的参数组合无法通过 {@link HttpTemplate#check()} 校验。
     */
    @Override
    public HttpTemplate load(String param) {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        HttpTemplate template = new HttpTemplate();

        System.out.println("\n" + "=".repeat(45));
        System.out.println("   HTTP REQUEST BUILDER");
        System.out.println("=".repeat(45));

        // 1. Host
        System.out.printf("Host [%s]: ", BasicConfig.getIP());
        String host = scanner.nextLine().trim();
        template.setHost(host.isEmpty() ? BasicConfig.getIP() : host);

        // 2. Port
        System.out.printf("Port [%d]: ", BasicConfig.getPORT());
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? BasicConfig.getPORT() : Integer.parseInt(portStr);
        template.setPort(port);

        // 3. Method
        System.out.print("Method [GET]: ");
        String m = scanner.nextLine().trim();
        template.setMethod(m.isEmpty() ? "GET" : m);

        // 4. Path
        System.out.print("Path [/]: ");
        String p = scanner.nextLine().trim();
        template.setPath(p.isEmpty() ? "/" : p);

        // 5. Body
        System.out.print("Body [Empty]: ");
        String b = scanner.nextLine().trim();
        template.setBody(b);

        // 6. Content-Type (Extra Headers)
        if (!b.isEmpty()) {
            System.out.print("Content-Type [application/json]: ");
            String ct = scanner.nextLine().trim();
            if (ct.isEmpty()) ct = "application/json";
            template.setHeaders("Content-Type: " + ct + "\r\n");
        }

        // 7. 校验并同步配置
        try {
            template.check();
        } catch (Exception e) {
            throw new RuntimeException("Invalid Input: " + e.getMessage());
        }

        System.out.println(">>> Template Ready.");
        return template;
    }
}