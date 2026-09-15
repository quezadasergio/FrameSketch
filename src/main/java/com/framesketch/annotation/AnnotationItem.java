package com.framesketch.annotation;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public abstract class AnnotationItem {

    private final long startTimeMs;
    private final long endTimeMs;

    protected AnnotationItem(long startTimeMs, long endTimeMs) {
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public boolean isVisibleAt(long timeMs) {
        return timeMs >= startTimeMs && timeMs <= endTimeMs;
    }

    public abstract void draw(GraphicsContext gc);
}
