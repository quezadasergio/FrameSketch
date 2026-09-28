package com.framesketch.annotation;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class AnnotationModel {

    public enum Tool {
        DRAW,
        RECTANGLE,
        ELLIPSE,
        TEXT
    }

    private final ObservableList<AnnotationItem> items = FXCollections.observableArrayList();
    private final Deque<AnnotationItem> undoStack = new ArrayDeque<>();

    private final ObjectProperty<Tool> tool = new SimpleObjectProperty<>(Tool.DRAW);
    private final ObjectProperty<Color> color = new SimpleObjectProperty<>(Color.web("#E11D48"));
    private final DoubleProperty strokeWidth = new SimpleDoubleProperty(4.0);
    private final DoubleProperty textSize = new SimpleDoubleProperty(24);
    private final BooleanProperty jitterEnabled = new SimpleBooleanProperty(false);
    private final DoubleProperty jitterAmplitude = new SimpleDoubleProperty(2.5);

    /** How long annotations remain visible after their start time (ms). */
    private final DoubleProperty visibleDurationMs = new SimpleDoubleProperty(Double.POSITIVE_INFINITY);

    public ObservableList<AnnotationItem> getItems() {
        return items;
    }

    public ObjectProperty<Tool> toolProperty() {
        return tool;
    }

    public Tool getTool() {
        return tool.get();
    }

    public void setTool(Tool value) {
        tool.set(value);
    }

    public ObjectProperty<Color> colorProperty() {
        return color;
    }

    public Color getColor() {
        return color.get();
    }

    public DoubleProperty strokeWidthProperty() {
        return strokeWidth;
    }

    public double getStrokeWidth() {
        return strokeWidth.get();
    }

    public DoubleProperty textSizeProperty() {
        return textSize;
    }

    public double getTextSize() {
        return textSize.get();
    }

    public void setTextSize(double value) {
        textSize.set(value);
    }

    public BooleanProperty jitterEnabledProperty() {
        return jitterEnabled;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled.get();
    }

    public DoubleProperty jitterAmplitudeProperty() {
        return jitterAmplitude;
    }

    public double getJitterAmplitude() {
        return jitterAmplitude.get();
    }

    public long resolveEndTime(long startTimeMs, long mediaLengthMs) {
        double duration = visibleDurationMs.get();
        if (Double.isInfinite(duration) || duration <= 0) {
            return mediaLengthMs > 0 ? mediaLengthMs : Long.MAX_VALUE / 4;
        }
        long end = startTimeMs + (long) duration;
        if (mediaLengthMs > 0) {
            return Math.min(end, mediaLengthMs);
        }
        return end;
    }

    public void add(AnnotationItem item) {
        items.add(item);
        undoStack.push(item);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        AnnotationItem last = undoStack.pop();
        items.remove(last);
        return true;
    }

    public void clear() {
        items.clear();
        undoStack.clear();
    }

    public List<AnnotationItem> visibleAt(long timeMs) {
        return items.stream().filter(item -> item.isVisibleAt(timeMs)).toList();
    }
}
