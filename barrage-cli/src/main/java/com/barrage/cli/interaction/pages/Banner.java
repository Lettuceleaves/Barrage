package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;

/**
 * Barrage CLI 的启动 Banner 展示组件。
 * <p>
 * 该类是一个静态工具类，负责在应用程序启动时向终端输出 ASCII 艺术字 Logo、
 * 核心版本信息以及底层技术栈摘要（如 Java 25 FFM）。它用于增强命令行界面的
 * 视觉识别度，并向用户确认当前运行的内核版本。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>ANSI 彩色输出：</b> 使用 {@link Ansi} 接口定义的颜色代码（红/青/黄）高亮关键信息。</li>
 * <li><b>版本元数据展示：</b> 显式打印 "True Zero-GC IoUring Engine" 标语及 Java 版本要求。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 该类仅包含无状态的静态方法，不持有任何成员变量，可被任意线程安全调用。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class Banner implements Ansi {

    /**
     * 向指定终端打印标准启动 Banner。
     * <p>
     * 输出内容包括：
     * <ul>
     * <li>红色的 "BARRAGE" ASCII 艺术字。</li>
     * <li>青色的 "Barrage Kernel" 标识。</li>
     * <li>黄色的版本号 (1.3) 和 Java 25 FFM API 标识。</li>
     * </ul>
     *
     * @param t 终端交互接口，用于执行具体的打印操作
     */
    public static void print(Terminal t) {
        t.info(RED + "██████╗  █████╗ ██████╗ ██████╗  █████╗  ██████╗ ███████╗" + RESET);
        t.info(RED + "██╔══██╗██╔══██╗██╔══██╗██╔══██╗██╔══██╗██╔════╝ ██╔════╝" + RESET);
        t.info(RED + "██████╔╝███████║██████╔╝██████╔╝███████║██║  ███╗█████╗  " + RESET);
        t.info(RED + "██╔══██╗██╔══██║██╔══██╗██╔══██╗██╔══██║██║   ██║██╔══╝  " + RESET);
        t.info(RED + "██████╔╝██║  ██║██║  ██║██║  ██║██║  ██║╚██████╔╝███████╗" + RESET);
        t.info(RED + "╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝" + RESET);

        t.info("==================================================================");
        t.info("   " + CYAN + "Barrage Kernel" + RESET + ": True Zero-GC IoUring Engine");
        t.info("   Version: " + YELLOW + "1.0" + RESET + " | Java 25 FFM API");
        t.info("==================================================================");
    }
}