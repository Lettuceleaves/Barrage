package com.barrage.engine;

import com.barrage.kernel.io.IoUring;
import com.barrage.kernel.io.NativeConstants;
import com.barrage.kernel.io.NativeSocket;
import com.barrage.protocol.HTTP.HttpTemplate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * 单次 HTTP 接口测试引擎 (One-Shot Engine)。
 * <p>
 * 该类提供了一个静态工具方法，用于在当前线程中同步执行一次 HTTP 请求。
 * 它不复用任何资源，不追求吞吐量，专门用于：
 * <ul>
 * <li>CI/CD 流水线中的健康检查 (Health Check)。</li>
 * <li>CLI 启动时的连通性验证 (Connectivity Probe)。</li>
 * <li>功能性单元测试。</li>
 * </ul>
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
@SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Class is final")
public final class StandardEngine {

    // 私有构造，工具类模式
    private StandardEngine() {}

    /**
     * 执行单次 HTTP 请求。
     *
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     * @param template   请求模板
     * @param timeoutMs  超时时间 (毫秒)
     * @return 服务端响应的字符串内容
     * @throws IOException 发生网络错误
     * @throws java.util.concurrent.TimeoutException 请求超时
     */
    public static String execute(String targetHost, int targetPort, HttpTemplate template, int timeoutMs)
            throws Exception {

        String targetIp = InetAddress.getByName(targetHost).getHostAddress();

        // 使用 Confined Arena，确保单线程内资源安全且自动释放
        try (Arena arena = Arena.ofConfined();
             IoUring ring = new IoUring(2)) { // 深度为2足够了：1个Send + 1个Read

            // 1. 建立连接 (阻塞式)
            NativeSocket socket = new NativeSocket();
            // 注册到资源清理链，防止异常退出导致 FD 泄露
            try (socket) {
                if (!socket.connect(targetIp, targetPort)) {
                    throw new IOException("Failed to connect to " + targetIp + ":" + targetPort);
                }
                int fd = socket.getFd();

                // 2. 准备请求数据
                byte[] reqBytes = template.toBytes();
                MemorySegment reqSegment = arena.allocate(reqBytes.length);
                MemorySegment.copy(MemorySegment.ofArray(reqBytes), 0, reqSegment, 0, reqBytes.length);

                // 3. 提交发送 (Send)
                MemorySegment sqe = ring.nextSqe();
                ring.prepSend(sqe, fd, reqSegment, (int) reqSegment.byteSize(), 0);
                sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, 1L); // Tag: 1 = Write
                ring.submit();

                // 4. 等待发送完成
                waitForCqe(ring, timeoutMs, "Sending request");

                // 5. 提交读取 (Read)
                // 申请一个足够大的缓冲区用于接收响应（假设响应不超过 64KB）
                MemorySegment readBuffer = arena.allocate(64 * 1024);
                sqe = ring.nextSqe();
                ring.prepRead(sqe, fd, readBuffer, (int) readBuffer.byteSize(), 0);
                sqe.set(ValueLayout.JAVA_LONG, NativeConstants.SQE_OFF_USER_DATA, 2L); // Tag: 2 = Read
                ring.submit();

                // 6. 等待响应完成 (带超时)
                IoUring.Cqe cqe = waitForCqe(ring, timeoutMs, "Waiting for response");

                // 7. 解析结果
                int bytesRead = cqe.res;
                if (bytesRead < 0) {
                    throw new IOException("Read failed with error code: " + bytesRead);
                }
                if (bytesRead == 0) {
                    throw new IOException("Server closed connection (Empty Response)");
                }

                // 将堆外内存转为 Java 字符串
                byte[] respBytes = new byte[bytesRead];
                MemorySegment.copy(readBuffer, 0, MemorySegment.ofArray(respBytes), 0, bytesRead);
                return new String(respBytes, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 轮询等待 CQE，支持超时控制。
     */
    private static IoUring.Cqe waitForCqe(IoUring ring, int timeoutMs, String phase)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IoUring.Cqe cqe = new IoUring.Cqe();

        while (true) {
            // 检查超时
            if (System.currentTimeMillis() > deadline) {
                throw new java.util.concurrent.TimeoutException("Timeout during phase: " + phase);
            }

            // 非阻塞尝试获取
            if (ring.peekCqe(cqe)) {
                // 如果是发送完成事件(res >= 0)，或者是读取完成事件
                // 注意：io_uring 的 res 在错误时为负数
                return cqe;
            }

            // 短暂休眠以避免空转占满 CPU (1ms 粒度对于单次测试足够了)
            // 不使用 LockSupport.parkNanos 以保持简单
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting");
            }
        }
    }
}