package com.framesketch.annotation;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongSupplier;

public class AnnotationCanvas extends Canvas {

    private final AnnotationModel model;
    private final LongSupplier timeSupplier;
    private final LongSupplier lengthSupplier;
    private final Random random = new Random();

    private final List<StrokeAnnotation.Point> currentPoints = new ArrayList<>();
    private boolean drawing;

    public AnnotationCanvas(AnnotationModel model, LongSupplier timeSupplier, LongSupplier lengthSupplier) {
        this.model = model;
        this.timeSupplier = timeSupplier;
        this.lengthSupplier = lengthSupplier;

        setFocusTraversable(true);
        addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);

        model.getItems().addListener((javafx.collections.ListChangeListener<? super AnnotationItem>) c -> redraw());
    }

    public void redraw() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());

        long time = timeSupplier.getAsLong();
        for (AnnotationItem item : model.visibleAt(time)) {
            item.draw(gc);
        }

        if (drawing && currentPoints.size() >= 2) {
            gc.setStroke(model.getColor());
            gc.setLineWidth(model.getStrokeWidth());
            gc.beginPath();
            StrokeAnnotation.Point first = currentPoints.getFirst();
            gc.moveTo(first.x(), first.y());
            for (int i = 1; i < currentPoints.size(); i++) {
                StrokeAnnotation.Point p = currentPoints.get(i);
                gc.lineTo(p.x(), p.y());
            }
            gc.stroke();
        } else if (drawing && currentPoints.size() == 1) {
            StrokeAnnotation.Point p = currentPoints.getFirst();
            gc.setFill(model.getColor());
            double w = model.getStrokeWidth();
            gc.fillOval(p.x() - w / 2, p.y() - w / 2, w, w);
        }
    }

    private void onPressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (model.getTool() == AnnotationModel.Tool.TEXT) {
            placeText(event.getX(), event.getY());
            return;
        }
        drawing = true;
        currentPoints.clear();
        currentPoints.add(new StrokeAnnotation.Point(event.getX(), event.getY()));
        redraw();
    }

    private void onDragged(MouseEvent event) {
        if (!drawing || model.getTool() != AnnotationModel.Tool.DRAW) {
            return;
        }
        currentPoints.add(new StrokeAnnotation.Point(event.getX(), event.getY()));
        redraw();
    }

    private void onReleased(MouseEvent event) {
        if (!drawing || model.getTool() != AnnotationModel.Tool.DRAW) {
            return;
        }
        drawing = false;
        if (currentPoints.isEmpty()) {
            return;
        }
        List<StrokeAnnotation.Point> points = new ArrayList<>(currentPoints);
        if (model.isJitterEnabled()) {
            points = new ArrayList<>(StrokeAnnotation.applyJitter(points, model.getJitterAmplitude(), random));
        }
        long start = timeSupplier.getAsLong();
        long end = model.resolveEndTime(start, lengthSupplier.getAsLong());
        model.add(new StrokeAnnotation(start, end, points, model.getColor(), model.getStrokeWidth()));
        currentPoints.clear();
        redraw();
    }

    private void placeText(double x, double y) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Texto en video");
        dialog.setHeaderText("Escribe la anotación");
        dialog.setContentText("Texto:");
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim).filter(s -> !s.isEmpty()).ifPresent(text -> {
            long start = timeSupplier.getAsLong();
            long end = model.resolveEndTime(start, lengthSupplier.getAsLong());
            Color color = model.getColor();
            double fontSize = Math.max(12, model.getStrokeWidth() * 4);
            model.add(new TextAnnotation(start, end, text, x, y, color, fontSize));
            redraw();
        });
    }
}
