package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;

/**
 * Barrage CLI 的静态信息展示组件。
 * <p>
 * 该类是一个无状态的工具类，专注于向终端用户输出非交互式的参考信息。
 * 它封装了项目元数据（如文档链接、GitHub 仓库地址）以及简要的操作手册，
 * 帮助用户在不离开命令行界面的情况下快速查阅相关资料。
 *
 * <h2>核心特性：</h2>
 * <ul>
 * <li><b>只读视图：</b> 仅负责数据的格式化展示，不涉及任何系统配置的修改。</li>
 * <li><b>交互式暂停：</b> 每个展示页面结束时都会阻塞等待用户按键，防止信息一闪而过。</li>
 * </ul>
 *
 * <h2>线程安全性：</h2>
 * <b>线程安全 (Thread-Safe)。</b>
 * 所有方法均为静态且不依赖任何共享的可变状态，可被任意 UI 线程调用。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class InfoViewer implements Ansi {

    /**
     * 展示项目文档与相关链接。
     * <p>
     * 在控制台打印 GitHub 仓库地址、Wiki 文档链接以及作者信息。
     * 输出完成后会挂起当前线程，直到用户按下回车键才返回主菜单。
     *
     * @param t 终端交互接口，用于输出 ANSI 格式化的超链接文本
     */
    public static void showDocs(Terminal t) {
        t.section(BLUE + "Documentation & Links" + RESET);
        t.info("- GitHub:  " + CYAN + "https://github.com/LettuceLeaves/Barrage" + RESET);
        t.info("- Wiki:    " + CYAN + "https://github.com/LettuceLeaves/Barrage/wiki" + RESET);
        t.info("- Author:  " + YELLOW + "LettuceLeaves" + RESET);
        t.readString("Press Enter to return...", "");
    }

    /**
     * 展示简易使用手册 (Help Guide)。
     * <p>
     * 向用户解释 Barrage 的核心概念与操作模式：
     * <ul>
     * <li><b>核心架构：</b> 基于 io_uring 的高性能设计。</li>
     * <li><b>Benchmark Mode：</b> 标准压测模式与自测模式 (Self-Benchmark) 的区别。</li>
     * <li><b>Config：</b> 全局参数的配置方式。</li>
     * </ul>
     * 输出完成后会挂起当前线程，直到用户按下回车键。
     *
     * @param t 终端交互接口
     */
    public static void showHelp(Terminal t) {
        t.section(BLUE + "Help" + RESET);
        t.info("Barrage is a high-performance HTTP load generator based on " + YELLOW + "io_uring" + RESET + ".");
        t.info("");
        t.info(BLUE + "* Benchmark Mode:" + RESET + " Standard load testing.");
        t.info("  - " + YELLOW + "Self-Benchmark:" + RESET + " Spawns both server and client locally (Zero-Copy test).");
        t.info("  - " + YELLOW + "Stress Test:" + RESET + " Connects to a remote IP:Port.");
        t.info("");
        t.info(BLUE + "* Config:" + RESET + " Edit global parameters in GlobalConfig class or env vars.");
        t.readString("Press Enter to return...", "");
    }
}