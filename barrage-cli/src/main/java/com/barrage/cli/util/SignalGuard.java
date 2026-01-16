package com.barrage.cli.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * 作用域级系统信号守卫 (Scoped Signal Guard)。
 * <p>
 * 该类用于在特定的代码执行块期间拦截操作系统发送的 {@code SIGINT} 信号（通常由终端的 Ctrl+C 触发）。
 * 它采用 RAII (Resource Acquisition Is Initialization) 模式设计，配合 {@code try-with-resources} 语法，
 * 能够自动在进入代码块时注册自定义处理器，并在退出代码块时无缝恢复之前的处理器。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>临时拦截 (Ephemeral Interception)：</b> 仅在 {@code try} 块的作用域内接管信号控制权，
 * 保证了应用程序其他部分的信号处理逻辑不受污染。</li>
 * <li><b>现场恢复 (Context Restoration)：</b> 在资源释放时（{@code close()}），会自动将信号处理器还原为
 * 初始化之前的状态（无论是 JVM 默认处理器还是其他自定义处理器）。</li>
 * <li><b>优雅停机支持：</b> 为压测引擎提供“急停”能力，允许在用户强制中断时执行 {@code io_uring} 资源的清理和落盘操作。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>非线程安全 (Not Thread-Safe)。</b>
 * 底层 {@code Signal.handle} 修改的是 JVM 全局静态状态。
 * 该类应仅在主线程或单线程控制流中使用，避免多线程并发修改信号处理器导致竞态条件。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
@SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Class is final, preventing finalizer attacks")
public final class SignalGuard implements AutoCloseable {

    /**
     * 持有在此守卫激活之前的旧信号处理器。
     * 用于在 close 时进行现场恢复。
     */
    private final SignalHandler previousHandler;

    /**
     * 创建一个信号守卫并立即激活拦截。
     * <p>
     * 构造函数被调用时，会立即使用传入的 {@code onSignal} 逻辑替换当前的 {@code SIGINT} 处理器。
     *
     * @param onSignal 当捕获到 Ctrl+C 信号时需要执行的回调任务（通常是触发 shutdown 标志位或执行清理）。
     * 如果为 {@code null}，则信号触发时不做任何操作。
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
     * 关闭守卫并恢复现场。
     * <p>
     * 将 {@code SIGINT} 的处理器还原为构造时保存的 {@code previousHandler}。
     * 此方法由 try-with-resources 自动调用。
     */
    @Override
    public void close() {
        Signal.handle(new Signal("INT"), previousHandler);
    }
}