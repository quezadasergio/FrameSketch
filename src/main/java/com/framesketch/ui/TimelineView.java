package com.framesketch.ui;

import com.framesketch.media.MediaService;
import com.framesketch.timeline.TimelineModel;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Interactive playback timeline with playhead, keyframes and A/B markers.
 */
public class TimelineView extends Pane {

    private static final double TRACK_HEIGHT = 10;
    private static final double PADDING_X = 10;
    private static final double HIT_RADIUS = 10;

    private final MediaService media;
    private final TimelineModel timeline;
    private final Rectangle track = new Rectangle();
    private final Line playhead = new Line();
    private final Map<TimelineModel.Keyframe, Polygon> keyframeShapes = new HashMap<>();
    private final Circle markerAShape = new Circle(7);
    private final Text markerALabel = new Text("A");
    private final Circle markerBShape = new Circle(7);
    private final Text markerBLabel = new Text("B");

    private enum DragTarget { NONE, PLAYHEAD, KEYFRAME, MARKER_A, MARKER_B }

    private DragTarget dragTarget = DragTarget.NONE;
    private TimelineModel.Keyframe draggedKeyframe;
    private boolean suppressSeekFromMedia;

    public TimelineView(MediaService media, TimelineModel timeline) {
        this.media = media;
        this.timeline = timeline;
        getStyleClass().add("timeline-view");
        setPrefHeight(52);
        setMinHeight(52);
        setPadding(new Insets(8, 0, 4, 0));

        track.setHeight(TRACK_HEIGHT);
        track.setArcWidth(8);
        track.setArcHeight(8);
        track.getStyleClass().add("timeline-track");
        track.setFill(Color.web("#2e3748"));

        playhead.setStroke(Color.web("#f4f7fb"));
        playhead.setStrokeWidth(2);
        playhead.setMouseTransparent(true);

        markerAShape.setFill(Color.web("#38bdf8"));
        markerAShape.setStroke(Color.web("#0c4a6e"));
        markerAShape.setStrokeWidth(1.5);
        markerAShape.getStyleClass().add("marker-a");
        markerALabel.setFill(Color.web("#0c4a6e"));
        markerALabel.setFont(Font.font(9));
        markerALabel.setMouseTransparent(true);

        markerBShape.setFill(Color.web("#a3e635"));
        markerBShape.setStroke(Color.web("#3f6212"));
        markerBShape.setStrokeWidth(1.5);
        markerBShape.getStyleClass().add("marker-b");
        markerBLabel.setFill(Color.web("#3f6212"));
        markerBLabel.setFont(Font.font(9));
        markerBLabel.setMouseTransparent(true);

        getChildren().addAll(track, playhead, markerAShape, markerALabel, markerBShape, markerBLabel);

        InvalidationListener redraw = obs -> layoutMarkers();
        widthProperty().addListener(redraw);
        heightProperty().addListener(redraw);
        media.lengthMsProperty().addListener(redraw);
        media.timeMsProperty().addListener(obs -> {
            if (!suppressSeekFromMedia && dragTarget == DragTarget.NONE) {
                layoutMarkers();
            }
        });
        timeline.getKeyframes().addListener((javafx.collections.ListChangeListener<? super TimelineModel.Keyframe>) c -> rebuildKeyframes());
        timeline.selectedKeyframeProperty().addListener(redraw);
        timeline.markerAMsProperty().addListener(redraw);
        timeline.markerBMsProperty().addListener(redraw);

        setOnMousePressed(this::onPressed);
        setOnMouseDragged(this::onDragged);
        setOnMouseReleased(this::onReleased);
        setOnMouseClicked(this::onClicked);

        rebuildKeyframes();
        layoutMarkers();
    }

    private void rebuildKeyframes() {
        getChildren().removeAll(keyframeShapes.values());
        keyframeShapes.clear();
        for (TimelineModel.Keyframe kf : timeline.getKeyframes()) {
            Polygon diamond = new Polygon(0, -8, 7, 0, 0, 8, -7, 0);
            diamond.setFill(Color.web("#f59e0b"));
            diamond.setStroke(Color.web("#92400e"));
            diamond.setStrokeWidth(1.2);
            diamond.getStyleClass().add("keyframe-marker");
            diamond.setCursor(Cursor.HAND);
            keyframeShapes.put(kf, diamond);
            getChildren().add(diamond);
            kf.timeMsProperty().addListener(obs -> layoutMarkers());
        }
        // Keep playhead / markers above keyframes
        playhead.toFront();
        markerAShape.toFront();
        markerALabel.toFront();
        markerBShape.toFront();
        markerBLabel.toFront();
        layoutMarkers();
    }

    private void layoutMarkers() {
        double w = Math.max(1, getWidth());
        double trackY = 22;
        track.setX(PADDING_X);
        track.setY(trackY);
        track.setWidth(Math.max(1, w - PADDING_X * 2));

        long length = Math.max(1, media.lengthMsProperty().get());
        double playX = timeToX(media.getTimeMs(), length);
        playhead.setStartX(playX);
        playhead.setEndX(playX);
        playhead.setStartY(trackY - 10);
        playhead.setEndY(trackY + TRACK_HEIGHT + 10);

        for (Map.Entry<TimelineModel.Keyframe, Polygon> entry : keyframeShapes.entrySet()) {
            TimelineModel.Keyframe kf = entry.getKey();
            Polygon shape = entry.getValue();
            double x = timeToX(kf.getTimeMs(), length);
            shape.setLayoutX(x);
            shape.setLayoutY(trackY + TRACK_HEIGHT / 2);
            boolean selected = kf.equals(timeline.getSelectedKeyframe());
            shape.setFill(selected ? Color.web("#fb923c") : Color.web("#f59e0b"));
            shape.setStrokeWidth(selected ? 2.2 : 1.2);
        }

        boolean showA = timeline.hasMarkerA();
        markerAShape.setVisible(showA);
        markerALabel.setVisible(showA);
        if (showA) {
            double x = timeToX(timeline.getMarkerAMs(), length);
            markerAShape.setCenterX(x);
            markerAShape.setCenterY(trackY - 8);
            markerALabel.setX(x - 3.5);
            markerALabel.setY(trackY - 5);
        }

        boolean showB = timeline.hasMarkerB();
        markerBShape.setVisible(showB);
        markerBLabel.setVisible(showB);
        if (showB) {
            double x = timeToX(timeline.getMarkerBMs(), length);
            markerBShape.setCenterX(x);
            markerBShape.setCenterY(trackY + TRACK_HEIGHT + 8);
            markerBLabel.setX(x - 3.5);
            markerBLabel.setY(trackY + TRACK_HEIGHT + 11);
        }
    }

    private double timeToX(long timeMs, long lengthMs) {
        double ratio = Math.max(0, Math.min(1, timeMs / (double) lengthMs));
        return PADDING_X + ratio * track.getWidth();
    }

    private long xToTime(double x) {
        long length = Math.max(1, media.lengthMsProperty().get());
        double ratio = (x - PADDING_X) / Math.max(1, track.getWidth());
        ratio = Math.max(0, Math.min(1, ratio));
        return Math.round(ratio * length);
    }

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (e.isShiftDown()
                && findKeyframeAt(e.getX(), e.getY()) == null
                && !hitCircle(markerAShape, e.getX(), e.getY())
                && !hitCircle(markerBShape, e.getX(), e.getY())) {
            timeline.addKeyframe(xToTime(e.getX()));
            e.consume();
            return;
        }
        TimelineModel.Keyframe nearKf = findKeyframeAt(e.getX(), e.getY());
        if (nearKf != null) {
            dragTarget = DragTarget.KEYFRAME;
            draggedKeyframe = nearKf;
            timeline.setSelectedKeyframe(nearKf);
            suppressSeekFromMedia = true;
            e.consume();
            return;
        }
        if (timeline.hasMarkerA() && hitCircle(markerAShape, e.getX(), e.getY())) {
            dragTarget = DragTarget.MARKER_A;
            suppressSeekFromMedia = true;
            e.consume();
            return;
        }
        if (timeline.hasMarkerB() && hitCircle(markerBShape, e.getX(), e.getY())) {
            dragTarget = DragTarget.MARKER_B;
            suppressSeekFromMedia = true;
            e.consume();
            return;
        }
        dragTarget = DragTarget.PLAYHEAD;
        suppressSeekFromMedia = true;
        seekToX(e.getX());
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        switch (dragTarget) {
            case PLAYHEAD -> seekToX(e.getX());
            case KEYFRAME -> {
                if (draggedKeyframe != null) {
                    draggedKeyframe.setTimeMs(xToTime(e.getX()));
                    layoutMarkers();
                }
            }
            case MARKER_A -> {
                timeline.setMarkerA(xToTime(e.getX()));
                layoutMarkers();
            }
            case MARKER_B -> {
                timeline.setMarkerB(xToTime(e.getX()));
                layoutMarkers();
            }
            default -> {
            }
        }
        e.consume();
    }

    private void onReleased(MouseEvent e) {
        if (dragTarget == DragTarget.KEYFRAME) {
            timeline.sortKeyframes();
        }
        dragTarget = DragTarget.NONE;
        draggedKeyframe = null;
        suppressSeekFromMedia = false;
        layoutMarkers();
    }

    private void onClicked(MouseEvent e) {
        if (e.getButton() == MouseButton.SECONDARY) {
            TimelineModel.Keyframe nearKf = findKeyframeAt(e.getX(), e.getY());
            if (nearKf != null) {
                timeline.removeKeyframe(nearKf);
            }
            e.consume();
            return;
        }
        if (e.getButton() == MouseButton.PRIMARY
                && e.getClickCount() == 2
                && findKeyframeAt(e.getX(), e.getY()) == null
                && !hitCircle(markerAShape, e.getX(), e.getY())
                && !hitCircle(markerBShape, e.getX(), e.getY())) {
            timeline.addKeyframe(xToTime(e.getX()));
            e.consume();
        }
    }

    private void seekToX(double x) {
        long time = xToTime(x);
        media.seek(time);
        layoutMarkers();
    }

    private TimelineModel.Keyframe findKeyframeAt(double x, double y) {
        TimelineModel.Keyframe best = null;
        double bestDist = HIT_RADIUS;
        for (Map.Entry<TimelineModel.Keyframe, Polygon> entry : keyframeShapes.entrySet()) {
            Polygon shape = entry.getValue();
            double dx = shape.getLayoutX() - x;
            double dy = shape.getLayoutY() - y;
            double dist = Math.hypot(dx, dy);
            if (dist <= bestDist) {
                bestDist = dist;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static boolean hitCircle(Circle circle, double x, double y) {
        if (!circle.isVisible()) {
            return false;
        }
        return Math.hypot(circle.getCenterX() - x, circle.getCenterY() - y) <= HIT_RADIUS;
    }
}
