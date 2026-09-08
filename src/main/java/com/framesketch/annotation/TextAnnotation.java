package com.framesketch.annotation;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public final class TextAnnotation extends AnnotationItem {

    private final String text;
    private final double x;
    private final double y;
    private final Color color;
    private final double fontSize;

    public TextAnnotation(
            long startTimeMs,
            long endTimeMs,
            String text,
            double x,
            double y,
            Color color,
            double fontSize
    ) {
        super(startTimeMs, endTimeMs);
        this.text = text;
        this.x = x;
        this.y = y;
        this.color = color;
        this.fontSize = fontSize;
    }

    @Override
    public void draw(GraphicsContext gc) {
        gc.setFill(color);
        gc.setFont(Font.font("Segoe UI", fontSize));
        gc.fillText(text, x, y);
    }
}
