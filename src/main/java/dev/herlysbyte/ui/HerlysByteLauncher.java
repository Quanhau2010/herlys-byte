package dev.herlysbyte.ui;

import javafx.application.Application;

/** Entry point kept separate from Application for reliable java -jar launching. */
public final class HerlysByteLauncher {
    private HerlysByteLauncher() { }

    public static void main(String[] args) {
        Application.launch(HerlysByteApp.class, args);
    }
}
