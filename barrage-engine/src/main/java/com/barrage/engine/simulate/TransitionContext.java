package com.barrage.engine.simulate;

/**
 * 流转上下文 (边)
 * 定义了从当前节点去往下一节点的规则。
 */
public class TransitionContext {

    /** 下一个节点的 ID */
    private String next;

    /** * 流转模式
     * e.g., "NO_DELAY", "WAIT_FIXED", "WAIT_RANDOM"
     */
    private String mode;

    /**
     * 模式参数 (缺失的字段)
     * e.g., "100" (毫秒), "0.5" (概率), "100-500" (范围)
     */
    private String value;

    // --- 构造函数 ---

    public TransitionContext() {}

    public TransitionContext(String next, String mode, String value) {
        this.next = next;
        this.mode = mode;
        this.value = value;
    }

    // --- Getters & Setters ---

    public String getNext() {
        return next;
    }

    public void setNext(String next) {
        this.next = next;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    // --- 缺失的 Getter ---
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}