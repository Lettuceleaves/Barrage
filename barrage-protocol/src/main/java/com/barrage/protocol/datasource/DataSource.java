package com.barrage.protocol.datasource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.io.IOException;

/**
 * 数据源抽象类。
 * <p>
 * 定义如何获取原始数据（Body），供上层协议封装使用。
 *
 * @author LettuceLeaves
 * @since 2026/1/11
 */
public abstract class DataSource {

    /**
     * 获取数据内容的大小（字节）。
     */
    public abstract long size() throws IOException;

    /**
     * 将数据加载到指定的内存区域中。
     *
     * @param arena 内存分配域 (通常是 Arena.global() 或 shared arena)
     * @return 包含数据的内存段
     */
    public abstract MemorySegment load(Arena arena) throws IOException;
}