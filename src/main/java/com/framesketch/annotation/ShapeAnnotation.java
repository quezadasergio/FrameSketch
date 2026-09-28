package com.framesketch.annotation;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

public final class ShapeAnnotation extends AnnotationItem {

    public enum Kind {
        RECTANGLE,
        ELLIPSE
    }

    private final Kind kind;
    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final Color color;
    private final double strokeWidth;

    public ShapeAnnotation(
            long startTimeMs,
            long endTimeMs,
            Kind kind,
            double x1,
            double y1,
            double x2,
            double y2,
            Color color,
            double strokeWidth
    ) {
        super(startTimeMs, endTimeMs);
        this.kind = kind;
        this.x = Math.min(x1, x2);
        this.y = Math.min(y1, y2);
        this.width = Math.abs(x2 - x1);
        this.height = Math.abs(y2 - y1);
        this.color = color;
        this.strokeWidth = strokeWidth;
    }

    @Override
    public void draw(GraphicsContext gc) {
        stroke(gc, kind, x, y, width, height, color, strokeWidth);
    }

    static void strokePreview(
            GraphicsContext gc,
            Kind kind,
            double x1,
            double y1,
            double x2,
            double y2,
            Color color,
            double strokeWidth
    ) {
        stroke(
                gc,
                kind,
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.abs(x2 - x1),
                Math.abs(y2 - y1),
                color,
                strokeWidth
        );
    }

    private static void stroke(
            GraphicsContext gc,
            Kind kind,
            double x,
            double y,
            double width,
            double height,
            Color color,
            double strokeWidth
    ) {
        gc.setStroke(color);
        gc.setLineWidth(strokeWidth);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        if (kind == Kind.ELLIPSE) {
            gc.strokeOval(x, y, width, height);
        } else {
            gc.strokeRect(x, y, width, height);
        }
    }
}
