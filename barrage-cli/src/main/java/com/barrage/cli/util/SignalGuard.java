package com.barrage.cli.util;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * 信号守卫：用于在特定代码块执行期间拦截 Ctrl+C (SIGINT) 信号。
 * 实现 AutoCloseable，配合 try-with-resources 使用，自动恢复旧的信号处理器。
 */
public class SignalGuard implements AutoCloseable {

    private final SignalHandler previousHandler;

    /**
     * @param onSignal 当捕获到 Ctrl+C 时要执行的逻辑（通常是停止任务、清理资源）
     */
    public SignalGuard(Runnable onSignal) {
        // 定义新的处理器
        SignalHandler newHandler = signal -> {
            // 执行传入的清理逻辑
            if (onSignal != null) {
                onSignal.run();
            }
        };

        // 注册新处理器，并持有旧处理器以便恢复
        // "INT" 对应操作系统的 SIGINT 信号 (Ctrl+C)
        this.previousHandler = Signal.handle(new Signal("INT"), newHandler);
    }

    /**
     * 恢复之前的信号处理器
     */
    @Override
    public void close() {
        Signal.handle(new Signal("INT"), previousHandler);
    }
}