package com.framesketch.app;

/**
 * Entry point for fat JAR / module-path launches.
 * JavaFX requires a non-{@code Application} main class when packaging as a shaded JAR.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        FrameSketchApp.main(args);
    }
}
