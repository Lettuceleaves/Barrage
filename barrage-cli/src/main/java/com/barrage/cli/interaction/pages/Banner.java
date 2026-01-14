package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.Ansi;
import com.barrage.cli.interaction.Terminal;

public class Banner implements Ansi {

    public static void print(Terminal t) {
        t.info(RED + "██████╗  █████╗ ██████╗ ██████╗  █████╗  ██████╗ ███████╗" + RESET);
        t.info(RED + "██╔══██╗██╔══██╗██╔══██╗██╔══██╗██╔══██╗██╔════╝ ██╔════╝" + RESET);
        t.info(RED + "██████╔╝███████║██████╔╝██████╔╝███████║██║  ███╗█████╗  " + RESET);
        t.info(RED + "██╔══██╗██╔══██║██╔══██╗██╔══██╗██╔══██║██║   ██║██╔══╝  " + RESET);
        t.info(RED + "██████╔╝██║  ██║██║  ██║██║  ██║██║  ██║╚██████╔╝███████╗" + RESET);
        t.info(RED + "╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝" + RESET);

        t.info("==================================================================");
        t.info("   " + CYAN + "Barrage Kernel" + RESET + ": True Zero-GC IoUring Engine");
        t.info("   Version: " + YELLOW + "1.3" + RESET + " | Java 25 FFM API");
        t.info("==================================================================");
    }
}