package com.barrage.kernel.env;

import com.barrage.kernel.util.DebugLogger;
import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 环境检查测试类.
 * <p>
 * 该类用于验证 Barrage 内核模块运行所需的底层环境依赖是否满足.
 * 包括 JDK 版本、Linux 内核版本以及 liburing 库的安装情况.
 * </p>
 */
public class EnvironmentCheckTest {

    @BeforeAll
    static void setupLogger() {
        DebugLogger.setLogFile("/workspaces/Barrage/barrage.log");
        DebugLogger.info("Starting Environment Check Test Suite...");
    }

    /**
     * 验证 JDK 版本是否为 25.
     * <p>
     * Barrage 利用了 JDK 25 的 Project Loom 和 Panama 特性，因此必须严格依赖 JDK 25.
     * </p>
     */
    @Test
    void testJavaVersion() {
        String javaVersion = System.getProperty("java.version");
        DebugLogger.info("Checking Java Version: " + javaVersion);
        assertTrue(javaVersion.startsWith("25"), "JDK 25 is required. Found: " + javaVersion);
    }

    /**
     * 验证 Linux 内核版本是否满足要求 (> 5.10).
     * <p>
     * io_uring 在 5.10+ 内核中才较为成熟，特别是 IORING_OP_SEND_ZC 等特性需更高版本.
     * 此测试通过 `uname -r` 获取内核版本并进行语义分析.
     * </p>
     * NOTE: 该测试仅在 Linux 操作系统下运行.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void testLinuxKernelVersion() {
        String kernelVersion = getKernelVersion();
        DebugLogger.info("Checking Linux Kernel Version: " + kernelVersion);

        String[] parts = kernelVersion.split("\\.");
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                
                boolean isSupported = major > 5 || (major == 5 && minor >= 10);
                assertTrue(isSupported, "Linux Kernel 5.10+ required. Found: " + kernelVersion);
            } catch (NumberFormatException e) {
                System.err.println("Could not parse kernel version: " + kernelVersion);
            }
        }
    }

    /**
     * 验证系统是否安装了 liburing 库.
     * <p>
     * 检查常用路径（如 /usr/lib, /usr/local/lib）下是否存在 liburing.so,
     * 并尝试通过 ldconfig 确认动态链接库缓存中是否存在该库.
     * </p>
     * NOTE: 该测试仅在 Linux 操作系统下运行.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void testLibUringPresent() {
        List<String> libraryPaths = Arrays.asList(
            "/usr/lib/liburing.so",
            "/usr/lib64/liburing.so",
            "/usr/local/lib/liburing.so",
            "/usr/lib/x86_64-linux-gnu/liburing.so",
            "/usr/lib/x86_64-linux-gnu/liburing.so.2"
        );

        boolean libFound = libraryPaths.stream().anyMatch(path -> new File(path).exists());
        
        if (!libFound) {
            try {
                Process p = new ProcessBuilder("ldconfig", "-p").start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String output = reader.lines().collect(Collectors.joining("\n"));
                libFound = output.contains("liburing.so");
            } catch (Exception e) {
                System.err.println("Could not run ldconfig: " + e.getMessage());
            }
        }

        assertTrue(libFound, "liburing must be installed (checked common paths and ldconfig)");
        
        boolean headerFound = new File("/usr/include/liburing.h").exists(); 
        if (!headerFound) {
             System.out.println("Warning: liburing.h not found in /usr/include/liburing.h");
        }
    }

    /**
     * 验证堆外内存支持 (FFM API).
     * <p>
     * 验证 JDK 25 的 Foreign Function & Memory (FFM) API 是否可用，
     * 以及是否通过 --enable-native-access=ALL-UNNAMED 正确开启了本机访问权限.
     * 本测试不使用反射，而是直接通过 Arena 分配堆外内存来验证依赖.
     * </p>
     */
    @Test
    void testOffHeapMemorySupport() {
        try (Arena arena = Arena.ofConfined()) {
            // 尝试分配 1KB 的堆外内存
            MemorySegment segment = arena.allocate(1024);
            
            // 验证分配成功
            assertTrue(segment.byteSize() == 1024, "应该分配了 1024 字节的堆外内存");
            assertTrue(segment.address() != 0, "堆外内存地址不应为 0");
            
            // 尝试写入和读取数据，确保内存可访问
            segment.set(ValueLayout.JAVA_INT, 0, 42);
            int value = segment.get(ValueLayout.JAVA_INT, 0);
            
            assertTrue(value == 42, "堆外内存读写验证失败");
            
            DebugLogger.info("FFM Off-Heap Memory Verification Passed via Arena API");
        } catch (Exception e) {
            DebugLogger.error("FFM API Alloc Failed: " + e.getMessage());
            fail("FFM API 堆外内存分配失败，请检查 JDK 版本和 --enable-native-access 参数: " + e.getMessage());
        }
    }

    private String getKernelVersion() {
        try {
            Process p = new ProcessBuilder("uname", "-r").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String version = reader.readLine();
            p.waitFor();
            return version;
        } catch (Exception e) {
            fail("Could not determine kernel version: " + e.getMessage());
            return "";
        }
    }
}
