package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;

/**
 * 负责展示静态文档和帮助信息
 */
public class InfoViewer implements Ansi {

    public static void showDocs(Terminal t) {
        t.section(BLUE + "Documentation & Links" + RESET);
        t.info("- GitHub:  " + CYAN + "https://github.com/LettuceLeaves/Barrage" + RESET);
        t.info("- Wiki:    " + CYAN + "https://github.com/LettuceLeaves/Barrage/wiki" + RESET);
        t.info("- Author:  " + YELLOW + "LettuceLeaves" + RESET);
        t.readString("Press Enter to return...", "");
    }

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