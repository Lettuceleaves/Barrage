package com.barrage.protocol;

import java.util.Map;

/**
 * 交互式数据填充器。
 * 屏蔽了具体的输入源（可以是 Console, GUI, 或者是 Web Form）。
 */
public interface InteractiveFiller {
    /**
     * 根据协议规则定义的组件进行装填。
     */
    Map<String, Object> fill(ProtocolRule rule);
}