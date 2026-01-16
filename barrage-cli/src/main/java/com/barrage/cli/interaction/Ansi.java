package com.barrage.cli.interaction;

/**
 * ANSI 终端控制码常量池。
 * <p>
 * 该接口定义了标准的 VT100/ANSI 转义序列，用于在命令行界面 (CLI) 中实现
 * 文本着色、样式修饰（如加粗、下划线）以及视觉增强效果。
 * <p>
 * <b>设计模式：</b>
 * 该接口通常作为 <b>Mix-in（混入）接口</b> 使用。
 * UI 组件类（如 {@code Home}, {@code BenchmarkWizard}）通过 {@code implements Ansi}
 * 即可直接使用 {@code RED}、{@code BLUE} 等常量，无需编写冗长的 {@code Ansi.RED} 前缀，
 * 从而保持代码的整洁性和可读性。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>零运行时开销：</b> 所有字段均为编译时常量 (Compile-time Constants)，
 * 编译器会将其直接内联到字节码中，无运行时查找开销。</li>
 * <li><b>全色域支持：</b> 涵盖了标准 8 色、高亮 8 色 (Bright/AIXterm) 以及对应的背景色。</li>
 * </ul>
 *
 * <h2>使用注意事项：</h2>
 * 务必在彩色文本段落的末尾追加 {@link #RESET}，否则颜色属性会“泄漏”并污染后续的控制台输出。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public interface Ansi {

    // --- 基础样式 ---

    /**
     * 重置所有样式 (Reset All Attributes)。
     * <p>
     * 清除当前终端的所有颜色、背景和样式设置，恢复到终端默认状态。
     * <b>必须在每段彩色输出结束后调用。</b>
     */
    String RESET      = "\033[0m";

    /** 加粗 / 高亮 (Bold)。 */
    String BOLD       = "\033[1m";

    /** 斜体 (Italic)。注：部分终端可能不支持或显示为反色。 */
    String ITALIC     = "\033[3m";

    /** 下划线 (Underline)。 */
    String UNDERLINE  = "\033[4m";

    /** 反显 (Reverse Video)。交换前景色与背景色。 */
    String REVERSED   = "\033[7m";

    // --- 标准前景色 (Standard Foreground) ---
    String BLACK  = "\033[30m";
    String RED    = "\033[31m";
    String GREEN  = "\033[32m";
    String YELLOW = "\033[33m";
    String BLUE   = "\033[34m";
    String PURPLE = "\033[35m";
    String CYAN   = "\033[36m";
    String WHITE  = "\033[37m";

    // --- 高亮度前景色 (Bright Foreground) ---
    // 通常用于需要更醒目提示的场景
    String GRAY          = "\033[90m";
    String BRIGHT_RED    = "\033[91m";
    String BRIGHT_GREEN  = "\033[92m";
    String BRIGHT_YELLOW = "\033[93m";
    String BRIGHT_BLUE   = "\033[94m";
    String BRIGHT_PURPLE = "\033[95m";
    String BRIGHT_CYAN   = "\033[96m";
    String BRIGHT_WHITE  = "\033[97m";

    // --- 背景色 (Background) ---
    String BG_BLACK  = "\033[40m";
    String BG_RED    = "\033[41m";
    String BG_GREEN  = "\033[42m";
    String BG_YELLOW = "\033[43m";
    String BG_BLUE   = "\033[44m";
    String BG_PURPLE = "\033[45m";
    String BG_CYAN   = "\033[46m";
    String BG_WHITE  = "\033[47m";

    // --- 常用复合快捷样式 (Common Combinations) ---
    String BOLD_RED    = BOLD + RED;
    String BOLD_GREEN  = BOLD + GREEN;
    String BOLD_YELLOW = BOLD + YELLOW;
    String BOLD_BLUE   = BOLD + BLUE;
    String BOLD_CYAN   = BOLD + CYAN;
}