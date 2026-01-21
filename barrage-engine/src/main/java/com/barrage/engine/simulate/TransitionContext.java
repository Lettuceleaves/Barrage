package com.barrage.engine.simulate;

/**
 * 流转上下文 (Transition Context)。
 * <p>
 * 定义了图节点之间的连接关系 (Edge) 和流转规则。
 * 不仅仅是简单的指向下一个节点，还可以携带元数据来控制流转行为 (如延迟、概率等)。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class TransitionContext {

    /** 下一个节点的 ID (Target Node ID) */
    private String next;

    /**
     * 流转模式。
     * <p>
     * 指示引擎在流转时的行为策略。例如：
     * <ul>
     * <li>"NO_DELAY": 立即跳转 (默认)</li>
     * <li>"WAIT_FIXED": 固定等待一段时间</li>
     * <li>"WAIT_RANDOM": 随机等待</li>
     * </ul>
     */
    private String mode;

    /**
     * 模式参数值。
     * <p>
     * 配合 {@code mode} 使用。例如 mode="WAIT_FIXED" 时，value="100" 表示等待 100ms。
     */
    private String value;

    // --- 构造函数 ---

    public TransitionContext() {
    }

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