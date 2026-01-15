package com.barrage.cli.interaction.pages;

import com.barrage.cli.interaction.*;
import com.barrage.cli.model.LaunchContext;

public class Home implements Ansi {

    public static LaunchContext open(Terminal t) {

        while (true) {
            t.section(BLUE + "Main Menu" + RESET);
            t.info(YELLOW + "1. Benchmark Mode" + RESET + "   (Default) - Interactive Configuration Wizard");
            t.info(YELLOW + "2. Showcase Mode" + RESET + "              - Quick Start (Default Settings)");
            t.info(YELLOW + "3. Settings" + RESET + "                   - Preferences & Global Config");
            t.info(YELLOW + "4. HTTP Templates" + RESET + "             - Select Request Message");
            t.info(YELLOW + "5. Docs & Links" + RESET + "               - Project Resources");
            t.info(YELLOW + "6. Help" + RESET + "                       - Usage Guide");
            t.info(YELLOW + "7. Exit" + RESET + "                       - Quit Application");

            String choice = t.readOption("Select Option", "1",
                    "1", "2", "3", "4", "5", "6", "7");

            switch (choice) {
                case "1":
                    return BenchmarkWizard.run(t);
                case "2":
                    return ShowcaseRunner.run(t);
                case "3":
                    SettingsManager.open(t);
                    break;
                case "4":
                    TemplatePage.open(t);
                    break;
                case "5":
                    InfoViewer.showDocs(t);
                    break;
                case "6":
                    InfoViewer.showHelp(t);
                    break;
                case "7":
                    return null;
            }
        }
    }
}