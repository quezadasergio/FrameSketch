package com.framesketch.annotation;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StrokeAnnotation extends AnnotationItem {

    public record Point(double x, double y) {
    }

    private final List<Point> points;
    private final Color color;
    private final double width;

    public StrokeAnnotation(long startTimeMs, long endTimeMs, List<Point> points, Color color, double width) {
        super(startTimeMs, endTimeMs);
        this.points = List.copyOf(points);
        this.color = color;
        this.width = width;
    }

    public List<Point> getPoints() {
        return points;
    }

    public Color getColor() {
        return color;
    }

    public double getWidth() {
        return width;
    }

    @Override
    public void draw(GraphicsContext gc) {
        if (points.size() < 2) {
            if (points.size() == 1) {
                gc.setFill(color);
                gc.fillOval(points.getFirst().x() - width / 2, points.getFirst().y() - width / 2, width, width);
            }
            return;
        }
        gc.setStroke(color);
        gc.setLineWidth(width);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        Point first = points.getFirst();
        gc.moveTo(first.x(), first.y());
        for (int i = 1; i < points.size(); i++) {
            Point p = points.get(i);
            gc.lineTo(p.x(), p.y());
        }
        gc.stroke();
    }

    public static List<Point> applyJitter(List<Point> source, double amplitude, java.util.Random random) {
        if (amplitude <= 0 || source.isEmpty()) {
            return source;
        }
        List<Point> jittered = new ArrayList<>(source.size());
        for (Point point : source) {
            double dx = (random.nextDouble() * 2 - 1) * amplitude;
            double dy = (random.nextDouble() * 2 - 1) * amplitude;
            jittered.add(new Point(point.x() + dx, point.y() + dy));
        }
        return Collections.unmodifiableList(jittered);
    }
}
