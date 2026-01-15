package com.barrage.protocol.datasource;

import com.barrage.protocol.HTTP.HttpTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于文件的静态数据源。
 * 职责：加载文件字节，并解析 Host 头以同步网络配置。
 */
public class FileDataSource implements DataSource {

    @Override
    public HttpTemplate load(String filePath) {
        try {
            System.out.println(">>> Loading file: " + filePath);

            // 1. 读取文件
            byte[] rawData = Files.readAllBytes(Path.of(filePath));

            // 2. 返回包含原生字节的 Template
            return new HttpTemplate(rawData);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load datasource file: " + filePath, e);
        }
    }
}