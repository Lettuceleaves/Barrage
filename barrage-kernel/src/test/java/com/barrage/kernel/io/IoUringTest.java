package com.barrage.kernel.io;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.foreign.MemorySegment;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IoUring} 的基础功能测试。
 * <p>
 * 测试项目包括：
 * <ul>
 *   <li>FFM Linker 和 libc 的基本交互验证</li>
 *   <li>IoUring 实例的初始化与关闭</li>
 *   <li>NOP（无操作）提交与完成流程验证</li>
 * </ul>
 * </p>
 */
public class IoUringTest {

    @Test
    public void testLinkerBasic() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        
        // Try to find getpid, which is usually everywhere
        MemorySegment getpidAddr = libc.find("getpid").orElse(null);
        if (getpidAddr == null) {
            // Try explicit load
            libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
            getpidAddr = libc.find("getpid").orElseThrow(() -> new RuntimeException("getpid not found"));
        }
        
        MethodHandle getpidNode = linker.downcallHandle(
            getpidAddr,
            FunctionDescriptor.of(ValueLayout.JAVA_INT)
        );
        
        int pid = (int) getpidNode.invokeExact();
        assertTrue(pid > 0);
        System.out.println("Linker verification passed. PID=" + pid);
    }

    @Test
    public void testInitAndClose() throws IOException {
        try (IoUring ring = new IoUring(8)) {
            assertNotNull(ring);
        }
    }

    @Test
    public void testNopSubmission() throws IOException {
        try (IoUring ring = new IoUring(8)) {
            MemorySegment sqe = ring.nextSqe();
            assertNotNull(sqe, "Should get a valid SQE");
            
            ring.prepNop(sqe);
            
            int submitted = ring.submitAndGet();
            assertEquals(1, submitted, "Should submit 1 SQE");
            
            int res = ring.waitComplete();
            assertEquals(0, res, "NOP should return 0");
        }
    }
}
