package com.barrage.protocol.HTTP;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * 0GC HTTP 响应解析视图 (Zero-Allocation Response View).
 * <p>
 * 职责：
 * 1. 提供对堆外内存 (MemorySegment) 的结构化访问。
 * 2. 解析 Status Code, Headers, Body。
 * 3. 避免将 bytes 转为 String，直接在 Native Memory 上做匹配。
 * <p>
 * 典型用法：
 * <pre>
 * HttpResponseView view = threadLocalView.get();
 * view.wrap(segment, length);
 * if (view.getStatusCode() == 200) {
 * MemorySegment body = view.getBody();
 * // ...
 * }
 * </pre>
 */
public class HttpResponseView {

    // 被包装的数据源
    private MemorySegment segment;
    private int length;

    // 缓存解析结果 (避免重复计算)
    private int cachedStatus = -1;
    private int bodyOffset = -1;

    public HttpResponseView() {
        // 无参构造，用于对象复用
    }

    /**
     * 重置并包装新的内存片段 (Hot Path)
     *
     * @param segment 包含 HTTP 响应数据的内存段 (通常是 Slice)
     * @param length  有效数据长度 (因为 segment 可能比实际数据大)
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Zero-copy design: view intentionally wraps external MemorySegment without copying")
    public void wrap(MemorySegment segment, int length) {
        this.segment = segment;
        this.length = length;
        // 重置缓存状态
        this.cachedStatus = -1;
        this.bodyOffset = -1;
    }

    /**
     * 获取 HTTP 状态码
     * <p>
     * 假设响应以标准 "HTTP/1.1 200 " 开头。
     * 直接读取偏移量 9, 10, 11 的字节进行计算，不进行字符串转换。
     */
    public int getStatusCode() {
        if (cachedStatus != -1) return cachedStatus;

        // 最小长度检查: "HTTP/1.1 200" 是 12 字节
        if (length < 12) return 0;

        // 快速解析：假设是 HTTP/1.x，前缀长度固定为 9 ("HTTP/1.1 ")
        // 状态码位于 index 9, 10, 11
        byte d1 = segment.get(ValueLayout.JAVA_BYTE, 9);  // 百位
        byte d2 = segment.get(ValueLayout.JAVA_BYTE, 10); // 十位
        byte d3 = segment.get(ValueLayout.JAVA_BYTE, 11); // 个位

        // 简单的 ASCII 转 Int: '0' 的 ASCII 码是 48
        // 如果遇到非数字字符，这种算法会算出奇怪的值，但在压测场景下可接受，
        // 或者可以增加简单的 Character.isDigit 检查。
        this.cachedStatus = (d1 - 48) * 100 + (d2 - 48) * 10 + (d3 - 48);

        return this.cachedStatus;
    }

    /**
     * 获取 Body 部分的内存切片 (Zero-Copy)
     * <p>
     * 扫描双换行符 (\r\n\r\n) 定位 Header 结束位置。
     */
    public MemorySegment getBody() {
        int start = findBodyOffset();
        if (start >= length) {
            return MemorySegment.NULL;
        }
        // 返回 Body 的切片
        return segment.asSlice(start, length - start);
    }

    /**
     * 判断 Body 是否包含指定的 ASCII 字符串 (Zero-Alloc)
     * <p>
     * 这是一个简单的暴力匹配 (Brute-Force)，对于短 Body 效率很高。
     * 如果需要匹配长 Body，建议优化为 KMP 或 Boyer-Moore。
     */
    public boolean bodyContains(String needle) {
        if (needle == null || needle.isEmpty()) return true;

        MemorySegment body = getBody();
        if (body.byteSize() == 0) return false;

        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        long bodyLen = body.byteSize();
        int targetLen = target.length;

        // 暴力匹配循环
        outer:
        for (long i = 0; i <= bodyLen - targetLen; i++) {
            for (int j = 0; j < targetLen; j++) {
                byte b = body.get(ValueLayout.JAVA_BYTE, i + j);
                if (b != target[j]) {
                    continue outer; // 字符不匹配，移动到 Body 的下一个位置
                }
            }
            return true; // 找到了
        }
        return false;
    }

    /**
     * 仅用于调试：将完整响应转为 String
     * 注意：这将产生大量垃圾，严禁在压测 Hot Path 中调用。
     */
    @Override
    public String toString() {
        if (segment == null || length == 0) return "";
        byte[] bytes = new byte[length];
        MemorySegment.copy(segment, 0, MemorySegment.ofArray(bytes), 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // =========================================================
    // Internal Helpers
    // =========================================================

    private int findBodyOffset() {
        if (bodyOffset != -1) return bodyOffset;

        // 扫描 "\r\n\r\n" (13, 10, 13, 10)
        // 从头开始扫描
        for (int i = 0; i < length - 3; i++) {
            // 优化：先判断第一个和第三个字节 (13)
            if (segment.get(ValueLayout.JAVA_BYTE, i) == 13 &&
                    segment.get(ValueLayout.JAVA_BYTE, i + 2) == 13) {

                // 再判断第二个和第四个 (10)
                if (segment.get(ValueLayout.JAVA_BYTE, i + 1) == 10 &&
                        segment.get(ValueLayout.JAVA_BYTE, i + 3) == 10) {

                    this.bodyOffset = i + 4;
                    return this.bodyOffset;
                }
            }
        }

        // 未找到 Body，返回 length 表示无 Body
        this.bodyOffset = length;
        return length;
    }
}