package com.barrage.kernel.memory;

import com.barrage.kernel.util.DebugLogger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存区域管理器测试类.
 */
public class MemoryArenaTest {

    @BeforeAll
    static void setup() {
        DebugLogger.setLogFile("/workspaces/Barrage/barrage.log");
        DebugLogger.info("Starting MemoryArena Test Suite...");
    }

    @Test
    void testAllocation() {
        DebugLogger.info("Testing basic allocation...");
        try (MemoryArena arena = new MemoryArena()) {
            MemorySegment segment = arena.allocate(1024);
            assertEquals(1024, segment.byteSize());
            assertTrue(segment.address() != 0);
            
            // Write and Read
            segment.set(ValueLayout.JAVA_INT, 0, 12345);
            int value = segment.get(ValueLayout.JAVA_INT, 0);
            assertEquals(12345, value);
            DebugLogger.info("Allocation and Access success.");
        }
    }

    @Test
    void testUnsafeAccessAfterClose() {
        DebugLogger.info("Testing access after close (expecting exception)...");
        MemorySegment segment;
        try (MemoryArena arena = new MemoryArena()) {
            segment = arena.allocate(100);
        } // Closed here

        // JDK 25 FFM throws IllegalStateException when accessing closed segment
        try {
            segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            fail("Should throw IllegalStateException when accessing closed segment");
        } catch (IllegalStateException e) {
            DebugLogger.info("Caught expected IllegalStateException: " + e.getMessage());
        }
    }
    
    @Test
    void testAlignedAllocation() {
        DebugLogger.info("Testing aligned allocation...");
        long alignment = 256;
        try (MemoryArena arena = new MemoryArena()) {
            MemorySegment segment = arena.allocate(1024, alignment);
            assertEquals(1024, segment.byteSize());
            
            // Check alignment: address % alignment == 0
            long address = segment.address();
            DebugLogger.info("Segment address: " + address + ", Alignment: " + alignment);
            assertEquals(0, address % alignment, "Address should be aligned to " + alignment);
        }
    }
}
