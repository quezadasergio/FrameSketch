package com.framesketch.playlist;

public enum PlayMode {
    SINGLE("Solo un video"),
    SEQUENCE("En fila"),
    REPEAT_ONE("Repetir uno"),
    REPEAT_ALL("Repetir todos");

    private final String label;

    PlayMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
