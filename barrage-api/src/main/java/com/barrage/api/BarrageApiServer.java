package com.barrage.api;

import com.barrage.api.handler.ConfigHandler;
import com.barrage.api.handler.ControlHandler;
import com.barrage.api.handler.SimulateHandler;
import com.barrage.api.handler.StaticFileHandler;
import com.barrage.kernel.config.basic.BasicConfig;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Barrage API 服务器主入口。
 * <p>
 * 使用 JDK 内置的 {@link com.sun.net.httpserver.HttpServer} 实现 HTTP 服务，
 * 无需任何第三方框架，开箱即用。
 *
 * <h2>API 路由总览：</h2>
 * <pre>
 * === 配置管理 =====================================
 * GET  /api/config                   → 读取当前配置（config.toml）
 * POST /api/config                   → 更新并保存配置（config.toml）
 * GET  /api/config/templates         → 列出所有 HTTP 模板名称
 * GET  /api/config/templates/{name}  → 获取指定模板详情
 * POST /api/config/templates         → 新增或更新模板（http.toml）
 *
 * === 引擎控制 =====================================
 * POST /api/control/start            → 启动压测引擎
 * POST /api/control/stop             → 停止压测引擎
 * GET  /api/control/status           → 查询引擎运行状态和实时指标
 * POST /api/control/qps?value=N      → 动态调整目标 QPS
 * </pre>
 *
 * <h2>启动方式：</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -jar barrage-api.jar [port]
 * </pre>
 * port 默认为 9090。
 */
public class BarrageApiServer {

    /** API 服务器默认监听端口 */
    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws Exception {
        // 1. 解析命令行端口参数
        int apiPort = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                apiPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[API] 端口参数无效，使用默认端口: " + DEFAULT_PORT);
            }
        }

        // 2. 加载内核配置（BasicConfig 必须在服务器启动前完成初始化）
        System.out.println(">>> [API] 正在加载内核配置...");
        BasicConfig.load();
        System.out.println(">>> [API] 内核配置加载完成");

        // 3. 创建 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(apiPort), 0);

        // 4. 注册路由（前缀匹配，JDK HttpServer 使用最长前缀规则）
        ConfigHandler  configHandler  = new ConfigHandler();
        ControlHandler controlHandler = new ControlHandler();
        SimulateHandler simulateHandler = new SimulateHandler();

        // /api/config/* 路由
        server.createContext("/api/config",   configHandler);
        // /api/control/* 路由
        server.createContext("/api/control",  controlHandler);
        // /api/simulate/* 路由（场景文件管理 + 模拟测试启停）
        server.createContext("/api/simulate", simulateHandler);

        // 静态文件路由（前端 SPA），最短前缀兜底
        server.createContext("/", new StaticFileHandler());

        // 5. 配置线程池（避免阻塞 I/O 造成饥饿）
        server.setExecutor(Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2,
                r -> {
                    Thread t = new Thread(r, "api-worker");
                    t.setDaemon(false);
                    return t;
                }
        ));

        // 6. 启动
        server.start();
        System.out.println(">>> [API] Barrage API 服务器已启动，监听端口: " + apiPort);
        System.out.println(">>> [API] 配置 API: http://localhost:" + apiPort + "/api/config");
        System.out.println(">>> [API] 控制 API: http://localhost:" + apiPort + "/api/control/status");

        // 7. 注册优雅停机钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n>>> [API] 正在关闭服务器...");
            server.stop(3);
            System.out.println(">>> [API] 服务器已关闭");
        }, "api-shutdown-hook"));
    }
}
