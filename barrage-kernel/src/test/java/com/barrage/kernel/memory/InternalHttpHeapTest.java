package com.barrage.kernel.memory;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部堆外内存 HTTP 请求存储测试 (InternalHttpHeapTest).
 * <p>
 * 该测试用于验证直接在堆外内存 (Off-Heap Memory) 中存储和管理硬编码的 HTTP 请求列表的功能。
 * 测试逻辑包括：
 * <ul>
 *   <li>从临时文件 (tmp/http_requests.txt) 读取 HTTP 请求样本</li>
 *   <li>解析请求并分配堆外内存片段 (MemorySegment) 进行存储</li>
 *   <li>验证存储内容的正确性</li>
 *   <li>测试批量释放内存片段的功能</li>
 * </ul>
 * </p>
 */
public class InternalHttpHeapTest {

    /**
     * 测试将 HTTP 请求存储到堆外内存并进行管理.
     * <p>
     * 步骤：
     * 1. 定位并读取包含 HTTP 请求的样本文件.
     * 2. 使用 "###" 分隔符解析文件中的多个请求.
     * 3. 使用 {@link MemoryArena} 为每个请求分配堆外内存，并将字节写入其中.
     * 4. 验证已分配内存段的数量和内容的正确性.
     * 5. 调用 {@link MemoryArena#free(List)} 批量释放所有分配的内存段.
     * </p>
     *
     * @throws IOException 如果读取样本文件失败
     */
    @Test
    public void testStoreHttpRequestsOffHeap() throws IOException {
        Path filePath = Paths.get("tmp", "http_requests.txt");
        if (!Files.exists(filePath)) {
            filePath = Paths.get("..", "tmp", "http_requests.txt");
        }
        
        if (!Files.exists(filePath)) {
             filePath = Paths.get("/workspaces/Barrage/tmp/http_requests.txt");
        }

        assertTrue(Files.exists(filePath), "HTTP Request file must exist at " + filePath.toAbsolutePath());

        String fileContent = Files.readString(filePath);

        String[] requests = fileContent.split("###");
        
        List<MemorySegment> offHeapRequests = new ArrayList<>();

        try (MemoryArena arena = new MemoryArena()) {
            for (String request : requests) {
                String trimmedRequest = request.trim();
                if (trimmedRequest.isEmpty()) continue;
                
                byte[] bytes = trimmedRequest.getBytes(StandardCharsets.UTF_8);
                MemorySegment segment = arena.allocate(bytes.length);
                
                MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
                offHeapRequests.add(segment);
            }

            assertEquals(2, offHeapRequests.size(), "Should have parsed 2 requests");

            MemorySegment firstSeg = offHeapRequests.get(0);
            byte[] firstBytes = new byte[(int) firstSeg.byteSize()];
            MemorySegment.copy(firstSeg, ValueLayout.JAVA_BYTE, 0, firstBytes, 0, firstBytes.length);
            String firstReq = new String(firstBytes, StandardCharsets.UTF_8);
            assertTrue(firstReq.contains("GET /api/v1/status"), "First request verification failed");

            MemorySegment secondSeg = offHeapRequests.get(1);
            byte[] secondBytes = new byte[(int) secondSeg.byteSize()];
            MemorySegment.copy(secondSeg, ValueLayout.JAVA_BYTE, 0, secondBytes, 0, secondBytes.length);
            String secondReq = new String(secondBytes, StandardCharsets.UTF_8);
            assertTrue(secondReq.contains("POST /api/v1/login"), "Second request verification failed");
            
            arena.free(offHeapRequests);
        }
    }
}
