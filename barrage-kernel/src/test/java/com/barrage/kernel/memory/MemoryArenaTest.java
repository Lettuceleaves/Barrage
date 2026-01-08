package com.barrage.kernel.memory;

import com.barrage.kernel.util.DebugLogger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存区域管理器测试类 (MemoryArenaTest).
 * <p>
 * 用于验证 {@link MemoryArena} 的基本功能，包括：
 * <ul>
 *   <li>内存分配与读写</li>
 *   <li>生命周期管理（关闭后的安全检查）</li>
 *   <li>内存对齐分配</li>
 * </ul>
 * 验证底层 FFM API (Foreign Function & Memory API) 是否按预期工作。
 * </p>
 */
public class MemoryArenaTest {

    @BeforeAll
    static void setup() {
        DebugLogger.setLogFile("/workspaces/Barrage/barrage.log");
        DebugLogger.info("Starting MemoryArena Test Suite...");
    }

    /**
     * 测试基本内存分配与访问.
     * <p>
     * 验证：
     * 1. 分配指定大小的内存段.
     * 2. 内存段地址非空.
     * 3. 可以正确写入和读取数据 (int 类型).
     * </p>
     */
    @Test
    void testAllocation() {
        DebugLogger.info("Testing basic allocation...");
        try (MemoryArena arena = new MemoryArena()) {
            MemorySegment segment = arena.allocate(1024);
            assertEquals(1024, segment.byteSize());
            assertTrue(segment.address() != 0);

            segment.set(ValueLayout.JAVA_INT, 0, 12345);
            int value = segment.get(ValueLayout.JAVA_INT, 0);
            assertEquals(12345, value);
            DebugLogger.info("Allocation and Access success.");
        }
    }

    /**
     * 测试关闭后的非法访问 (Use-After-Free).
     * <p>
     * 验证当 {@link MemoryArena} 关闭后，尝试访问其分配的内存段应抛出 {@link IllegalStateException}.
     * 这是 FFM API 提供的安全性保证之一。
     * </p>
     */
    @Test
    void testUnsafeAccessAfterClose() {
        DebugLogger.info("Testing access after close (expecting exception)...");
        MemorySegment segment;
        try (MemoryArena arena = new MemoryArena()) {
            segment = arena.allocate(100);
        }

        try {
            segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            fail("Should throw IllegalStateException when accessing closed segment");
        } catch (IllegalStateException e) {
            DebugLogger.info("Caught expected IllegalStateException: " + e.getMessage());
        }
    }
    
    /**
     * 测试对齐内存分配.
     * <p>
     * 验证 {@link MemoryArena#allocate(long, long)} 方法能正确按照指定的字节对齐方式分配内存.
     * 检查返回内存段的起始地址是否能被对齐值整除.
     * </p>
     */
    @Test
    void testAlignedAllocation() {
        DebugLogger.info("Testing aligned allocation...");
        long alignment = 256;
        try (MemoryArena arena = new MemoryArena()) {
            MemorySegment segment = arena.allocate(1024, alignment);
            assertEquals(1024, segment.byteSize());
            
            long address = segment.address();
            DebugLogger.info("Segment address: " + address + ", Alignment: " + alignment);
            assertEquals(0, address % alignment, "Address should be aligned to " + alignment);
        }
    }
}
