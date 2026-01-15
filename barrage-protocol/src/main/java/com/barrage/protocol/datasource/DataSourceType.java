package com.barrage.protocol.datasource;

/**
 * 数据源类型枚举。
 * <p>
 * 用于 CLI 交互阶段区分用户是希望通过控制台手输请求，
 * 还是从本地文件加载请求。
 */
public enum DataSourceType {

    /**
     * 交互式控制台输入 (Interactive)
     */
    CONSOLE,

    /**
     * 本地文件加载 (Static File)
     */
    FILE;

}