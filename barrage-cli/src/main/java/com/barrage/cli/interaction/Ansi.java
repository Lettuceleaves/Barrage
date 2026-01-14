package com.barrage.cli.interaction;

/**
 * ANSI 颜色及样式常量池。
 */
public interface Ansi {
    // --- 基础样式 ---
    String RESET      = "\033[0m";
    String BOLD       = "\033[1m";
    String ITALIC     = "\033[3m";
    String UNDERLINE  = "\033[4m";
    String REVERSED   = "\033[7m"; // 反色

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