package com.barrage.protocol;

import java.util.List;
import java.util.Map;

public interface ProtocolRule {
    /**
     * 定义该协议需要哪些字段（例如：Method, Path, Headers, Body）。
     */
    List<String> requiredComponents();

    /**
     * 根据装填好的组件拼装成最终报文。
     */
    String build(Map<String, Object> components);
}