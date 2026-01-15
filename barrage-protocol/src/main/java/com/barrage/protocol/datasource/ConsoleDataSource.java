package com.barrage.protocol.datasource;

import com.barrage.kernel.config.basic.BasicConfig;
import com.barrage.protocol.HTTP.HttpTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 基于控制台的交互式构建器。
 */
public class ConsoleDataSource implements DataSource {

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