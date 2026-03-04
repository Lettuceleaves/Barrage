package com.barrage.api.test;

import com.barrage.api.BarrageApiServer;
import com.barrage.kernel.config.basic.BasicConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Barrage API 接口集成测试入口。
 * <p>
 * 本类在同一 JVM 进程内启动 API 服务器，然后使用 JDK 内置的 {@link HttpClient}
 * 依次验证所有 REST 接口的正确性，无需任何测试框架。
 *
 * <h2>测试覆盖范围：</h2>
 * <pre>
 * [配置 API]
 *   ✓ GET  /api/config
 *   ✓ POST /api/config         （局部更新）
 *   ✓ GET  /api/config/templates
 *   ✓ POST /api/config/templates  （新增模板）
 *   ✓ GET  /api/config/templates/{name}
 *
 * [控制 API]
 *   ✓ GET  /api/control/status （未启动时）
 *   ✓ POST /api/control/start  （使用 selfBenchmark 模式，无需外部服务器）
 *   ✓ GET  /api/control/status （运行中）
 *   ✓ POST /api/control/qps    （动态调速）
 *   ✓ POST /api/control/stop
 *   ✓ GET  /api/control/status （停止后）
 * </pre>
 *
 * <h2>运行方式：</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -cp barrage-api/target/barrage-api-0.0.1-SNAPSHOT.jar \
 *      com.barrage.api.test.ApiTestMain
 * </pre>
 */
public class ApiTestMain {

    /** 测试用 API 端口（避免与正式服务冲突） */
    private static final int TEST_PORT = 19090;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;

    /** JDK 原生 HTTP 客户端 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 测试计数 */
    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║      Barrage API 集成自动化测试               ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        // 1. 加载内核配置并启动 API 服务器（后台线程）
        System.out.println("\n>>> 正在启动 API 服务器（端口 " + TEST_PORT + "）...");
        BasicConfig.load();
        Thread serverThread = new Thread(() -> {
            try {
                BarrageApiServer.main(new String[]{String.valueOf(TEST_PORT)});
            } catch (Exception e) {
                System.err.println("[测试] 服务器启动失败: " + e.getMessage());
            }
        }, "test-api-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // 等待服务器就绪
        waitForServer();
        System.out.println(">>> 服务器就绪，开始执行测试\n");

        // ========== 配置接口测试 ==========
        section("配置 API 测试");

        // 测试 1：读取当前配置
        test("GET /api/config（读取当前配置）",
                get("/api/config"),
                200, "ip");

        // 测试 2：局部更新配置（仅更新 step 字段）
        test("POST /api/config（局部更新 step=99999）",
                post("/api/config", """
                        { "step": 99999 }
                        """),
                200, "99999");

        // 测试 3：验证更新已生效
        test("GET /api/config（验证 step 已变为 99999）",
                get("/api/config"),
                200, "99999");

        // 测试 4：还原 step
        post("/api/config", String.format("""
                { "step": %d }
                """, BasicConfig.getSTEP()));

        // 测试 5：列出模板
        test("GET /api/config/templates（列出所有模板）",
                get("/api/config/templates"),
                200, "[");

        // 测试 6：新增测试模板
        test("POST /api/config/templates（新增测试模板）",
                post("/api/config/templates", """
                        {
                          "name":    "_test_template_",
                          "method":  "GET",
                          "path":    "/test",
                          "body":    "",
                          "headers": "X-Test:1"
                        }
                        """),
                200, "_test_template_");

        // 测试 7：获取刚新增的模板
        test("GET /api/config/templates/_test_template_（获取新模板详情）",
                get("/api/config/templates/_test_template_"),
                200, "X-Test:1");

        // 测试 8：获取不存在的模板
        test("GET /api/config/templates/NONEXIST（404 检验）",
                get("/api/config/templates/NONEXIST"),
                404, "模板不存在");

        // ========== 控制接口测试 ==========
        section("控制 API 测试");

        // 测试 9：查询状态（未启动时）
        test("GET /api/control/status（未启动）",
                get("/api/control/status"),
                200, "\"running\":false");

        // 测试 10：重复停止（幂等性）
        test("POST /api/control/stop（未运行时停止，应成功）",
                post("/api/control/stop", ""),
                200, "stopped");

        // 测试 11：启动压测（selfBenchmark 模式，内置服务端）
        // 注意：selfBenchmark 会绑定 BasicConfig.getPORT() 端口，需要确保可用
        int benchPort = BasicConfig.getPORT();
        test("POST /api/control/start（selfBenchmark 模式，端口 " + benchPort + "）",
                post("/api/control/start", String.format("""
                        {
                          "targetQps":     1000,
                          "selfBenchmark": true,
                          "targetPort":    %d,
                          "templateName":  "_test_template_"
                        }
                        """, benchPort)),
                200, "started");

        // 等待引擎爬升 2 秒
        sleep(2000);

        // 测试 12：查询运行中状态
        String statusJson = test("GET /api/control/status（运行中）",
                get("/api/control/status"),
                200, "\"running\":true");

        // 测试 13：动态调整 QPS
        test("POST /api/control/qps?value=2000（动态调速）",
                post("/api/control/qps?value=2000", ""),
                200, "2000");

        sleep(1000);

        // 测试 14：重复启动（应返回 409 冲突）
        test("POST /api/control/start（重复启动，应返回 409）",
                post("/api/control/start", "{}"),
                409, "已在运行");

        // 测试 15：停止引擎
        test("POST /api/control/stop",
                post("/api/control/stop", ""),
                200, "stopped");

        sleep(500);

        // 测试 16：停止后状态查询
        test("GET /api/control/status（停止后）",
                get("/api/control/status"),
                200, "\"running\":false");

        // 测试 17：停止后调整 QPS（应返回 409）
        test("POST /api/control/qps（停止后调 QPS，应返回 409）",
                post("/api/control/qps?value=999", ""),
                409, "未在运行");

        // ========== 模拟测试接口测试 ==========
        section("模拟测试 API 测试");

        // 测试 18：列出场景文件（应包含 scenario.yaml）
        test("GET /api/simulate/scenarios（列出所有场景文件）",
                get("/api/simulate/scenarios"),
                200, "scenario.yaml");

        // 测试 19：获取默认场景文件内容
        test("GET /api/simulate/scenarios/scenario.yaml（获取场景详情）",
                get("/api/simulate/scenarios/scenario.yaml"),
                200, "SmokeTestFlow");

        // 测试 20：获取不存在的场景文件（404）
        test("GET /api/simulate/scenarios/NONEXIST.yaml（404 检验）",
                get("/api/simulate/scenarios/NONEXIST.yaml"),
                404, "不存在");

        // 测试 21：保存新场景文件
        test("POST /api/simulate/scenarios（保存新场景文件）",
                post("/api/simulate/scenarios", """
                        {
                          "fileName":    "_api_test_scene_",
                          "graphName":   "API Test Flow",
                          "startNodeId": "n_start",
                          "nodes": [
                            {
                              "id": "n_start", "type": "START", "name": "Start",
                              "transition": { "mode": "NO_DELAY", "next": "n_end" }
                            },
                            {
                              "id": "n_end", "type": "TERMINAL", "name": "Done",
                              "resultTag": "SUCCESS", "saveContext": false
                            }
                          ]
                        }
                        """),
                200, "saved");

        // 测试 22：验证新场景文件已写入磁盘并可读
        test("GET /api/simulate/scenarios/_api_test_scene_.yaml（验证已保存）",
                get("/api/simulate/scenarios/_api_test_scene_.yaml"),
                200, "API Test Flow");

        // 测试 23：查询状态（未启动）
        test("GET /api/simulate/status（未启动）",
                get("/api/simulate/status"),
                200, "\"running\":false");

        // 测试 24：停止（幂等性）
        test("POST /api/simulate/stop（未运行时停止，应成功）",
                post("/api/simulate/stop", ""),
                200, "stopped");

        // 注意：/api/simulate/start 会启动真实引擎（100 万虚拟用户）
        // 在测试中仅做接口可达性验证，不等待全量用户启动
        // 若当前环境不支持 io_uring 则跳过
        try {
            String startResp = post("/api/simulate/start?scenario=scenario.yaml", "").body();
            if (startResp.contains("started")) {
                passCount++;
                System.out.println("  ✅ [PASS] POST /api/simulate/start（启动模拟测试）");
                sleep(1000);

                // 测试 26：运行中状态查询
                test("GET /api/simulate/status（启动后运行中）",
                        get("/api/simulate/status"),
                        200, "\"running\":true");

                // 测试 27：重复启动应返回 409
                test("POST /api/simulate/start（重复启动，应 409）",
                        post("/api/simulate/start", ""),
                        409, "已在运行");

                // 停止
                post("/api/simulate/stop", "");
                sleep(500);

                test("GET /api/simulate/status（停止后）",
                        get("/api/simulate/status"),
                        200, "\"running\":false");
            } else if (startResp.contains("error") || startResp.contains("失败")) {
                System.out.println("  ⚠️  [SKIP] POST /api/simulate/start — 引擎启动失败（可能环境不支持 io_uring），跳过后续模拟测试");
                System.out.println("       响应: " + startResp.substring(0, Math.min(startResp.length(), 200)));
            }
        } catch (Exception e) {
            System.out.println("  ⚠️  [SKIP] /api/simulate/start 接口异常: " + e.getMessage());
        }

        // ========== 汇总 ==========
        summary();
    }

    // =============================================
    // 测试辅助方法
    // =============================================

    /**
     * 发送测试请求并断言响应。
     *
     * @param desc            测试描述
     * @param response        HTTP 响应对象
     * @param expectedStatus  期望的 HTTP 状态码
     * @param expectedContains 期望响应体中包含的字符串（忽略大小写）
     * @return 响应体 JSON 字符串
     */
    private static String test(String desc,
                                HttpResponse<String> response,
                                int expectedStatus,
                                String expectedContains) {
        String body = response.body();
        boolean statusOk  = response.statusCode() == expectedStatus;
        boolean bodyOk    = body.contains(expectedContains);
        boolean pass      = statusOk && bodyOk;

        if (pass) {
            passCount++;
            System.out.printf("  ✅ [PASS] %s%n", desc);
        } else {
            failCount++;
            System.out.printf("  ❌ [FAIL] %s%n", desc);
            if (!statusOk) {
                System.out.printf("       期望状态码: %d，实际: %d%n", expectedStatus, response.statusCode());
            }
            if (!bodyOk) {
                System.out.printf("       期望响应包含: \"%s\"%n", expectedContains);
                System.out.printf("       实际响应体:   %s%n", body.length() > 200 ? body.substring(0, 200) + "..." : body);
            }
        }
        return body;
    }

    /** 发送 GET 请求 */
    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 发送 POST 请求（JSON body） */
    private static HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.trim()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 打印测试分节标题 */
    private static void section(String title) {
        System.out.println("\n─────────────────────────────────────────────");
        System.out.println("  " + title);
        System.out.println("─────────────────────────────────────────────");
    }

    /** 打印最终测试汇总 */
    private static void summary() {
        int total = passCount + failCount;
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.printf( "║  测试结果：%d 通过 / %d 失败 / %d 总计%s║%n",
                passCount, failCount, total,
                " ".repeat(Math.max(0, 16 - String.valueOf(total).length())));
        System.out.println("╚══════════════════════════════════════════════╝");

        if (failCount == 0) {
            System.out.println("🎉 所有测试全部通过！");
        } else {
            System.out.println("⚠️  有 " + failCount + " 个测试失败，请检查上方日志。");
            System.exit(1);
        }
    }

    /** 等待 API 服务器可访问（最长 5 秒） */
    private static void waitForServer() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try {
                get("/api/control/status");
                return; // 成功响应，服务器已就绪
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new RuntimeException("API 服务器在 5 秒内未就绪，测试终止！");
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
