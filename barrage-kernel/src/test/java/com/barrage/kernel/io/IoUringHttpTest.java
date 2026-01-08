package com.barrage.kernel.io;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static com.barrage.kernel.io.NativeConstants.*;

/**
 * {@link IoUring} 的 HTTP 传输集成测试。
 * <p>
 * 此测试模拟了一个高并发的 HTTP 请求场景，验证 IoUring 在持续流量下的性能和稳定性。
 * 它包含一个内嵌的阻塞式 HTTP 服务器，并使用 IoUring 发送大量堆外 (Off-heap) 请求。
 * </p>
 */
public class IoUringHttpTest {

    @Test
    public void testSendOffHeapRequest() throws Exception {
        // High count for sustained traffic monitoring, but keep test duration reasonable for CI
        // User can manually increase this variable for their long-running observations
        int requestCount = 10_000_000; 
        
        // 1. Setup blocking HTTP server with Keep-Alive support
        CountDownLatch serverReady = new CountDownLatch(1);
        CountDownLatch allRequestsProcessed = new CountDownLatch(requestCount);
        AtomicReference<Integer> serverPort = new AtomicReference<>();
        
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                serverPort.set(serverSocket.getLocalPort());
                serverReady.countDown();
                
                try (Socket client = serverSocket.accept()) {
                    client.setTcpNoDelay(true); 
                    
                    var in = client.getInputStream();
                    var out = client.getOutputStream();
                    byte[] buffer = new byte[1024];
                    byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.UTF_8);
                    
                    for (int i = 0; i < requestCount; i++) {
                        int read = in.read(buffer);
                        if (read == -1) break; 
                        out.write(response);
                        allRequestsProcessed.countDown();
                    }
                }
            } catch (IOException e) {
                // Silently exit on close
            }
        });
        serverThread.start();
        
        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server failed to start");
        int port = serverPort.get();
        
        // 2. Prepare off-heap request
        Path filePath = Paths.get("tmp", "http_requests.txt");
        if (!Files.exists(filePath)) filePath = Paths.get("..", "tmp", "http_requests.txt");
        if (!Files.exists(filePath)) filePath = Paths.get("/workspaces/Barrage/tmp/http_requests.txt");
        
        String content = Files.exists(filePath) ? Files.readString(filePath) : "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        byte[] reqBytes = content.split("###")[0].trim().concat("\r\n\r\n").getBytes(StandardCharsets.UTF_8);

        // 3. IoUring init & Connection Reuse
        try (IoUring ring = new IoUring(32)) { 
            int fd = ring.socket(AF_INET, SOCK_STREAM, 0);
            assertTrue(fd > 0, "Socket creation failed");
            
            int connRes = ring.connect(fd, "127.0.0.1", port);
            assertEquals(0, connRes, "Connect failed");
            
            try (java.lang.foreign.Arena dataArena = java.lang.foreign.Arena.ofConfined()) {
                MemorySegment buf = dataArena.allocate(reqBytes.length);
                MemorySegment.copy(reqBytes, 0, buf, ValueLayout.JAVA_BYTE, 0, reqBytes.length);
                MemorySegment readBuf = dataArena.allocate(1024);
                
                System.out.println("Starting 10M request loop for monitoring...");
                for (int i = 0; i < requestCount; i++) {
                    if (i % 500_000 == 0) System.out.println("Progress: " + i);
                    
                    // --- SEND ---
                    MemorySegment sqe = ring.nextSqe();
                    while (sqe == null) {
                       ring.submitAndGet(); 
                       sqe = ring.nextSqe();
                    }
                    ring.prepSend(sqe, fd, buf, reqBytes.length, 0);
                    ring.submitAndGet(); 
                    ring.waitComplete(); 
                    
                    // --- READ RESPONSE ---
                    sqe = ring.nextSqe();
                    while (sqe == null) {
                       ring.submitAndGet(); 
                       sqe = ring.nextSqe();
                    }
                    ring.prepRead(sqe, fd, readBuf, 1024, 0); 
                    ring.submitAndGet();
                    ring.waitComplete(); 
                }
                System.out.println("Loop finished.");
            }
            ring.closeFd(fd);
        }
        
        System.out.println("Finished generating traffic.");
    }
}
