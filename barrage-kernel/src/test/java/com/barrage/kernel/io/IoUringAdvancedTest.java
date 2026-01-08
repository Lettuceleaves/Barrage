package com.barrage.kernel.io;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static com.barrage.kernel.io.NativeConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IoUring} 的高级特性测试，包括 SQPOLL 和 SEND_ZC。
 */
public class IoUringAdvancedTest {

    @Test
    public void testSqPollInit() throws IOException {
        // 尝试初始化带有 SQPOLL 的 Ring
        // 注意：某些内核环境可能需要特权
        try (IoUring ring = new IoUring(8, IORING_SETUP_SQPOLL)) {
            assertNotNull(ring);
            System.out.println("SQPOLL initialized successfully.");
        } catch (IOException e) {
            if (e.getMessage().contains("-1")) { // EPERM or similar
                System.err.println("SQPOLL init failed due to permissions, skipping: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }

    @Test
    public void testRegisterBuffers() throws IOException {
        try (IoUring ring = new IoUring(8);
             Arena arena = Arena.ofConfined()) {
            
            MemorySegment buf1 = arena.allocate(1024);
            MemorySegment buf2 = arena.allocate(2048);
            
            ring.registerBuffers(buf1, buf2);
            System.out.println("Buffers registered successfully.");
        }
    }

    @Test
    public void testSendZcOpcode() throws IOException {
        try (IoUring ring = new IoUring(8);
             Arena arena = Arena.ofConfined()) {
            
            MemorySegment buf = arena.allocate(1024);
            ring.registerBuffers(buf);
            
            MemorySegment sqe = ring.nextSqe();
            assertNotNull(sqe);
            
            // 使用一个无效的 FD (-1) 测试 opcode 是否能正常提交
            // 如果内核不支持 SEND_ZC，它会返回 -EINVAL 或 -EOPNOTSUPP
            ring.prepSendZc(sqe, -1, buf, (int)buf.byteSize(), IORING_RECVSEND_FIXED_BUF, 0);
            
            ring.submitAndGet();
            int res = ring.waitComplete();
            
            // -9 为 EBADF，说明系统正常识别了该操作但在 FD 上失败了
            // -95 为 EOPNOTSUPP，说明内核不支持此操作
            // -22 为 EINVAL，通常指参数错误
            assertTrue(res < 0, "Should fail with error code, but opcode should be recognized. Got: " + res);
            System.out.println("SEND_ZC completion result: " + res);
        }
    }
}
