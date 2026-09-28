package com.framesketch.annotation;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongSupplier;

public class AnnotationCanvas extends Canvas {

    private static final double MIN_SHAPE_SIZE = 2;

    private final AnnotationModel model;
    private final LongSupplier timeSupplier;
    private final LongSupplier lengthSupplier;
    private final Random random = new Random();

    private final List<StrokeAnnotation.Point> currentPoints = new ArrayList<>();
    private boolean drawing;
    private double startX;
    private double startY;
    private double currentX;
    private double currentY;

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

        if (!drawing) {
            return;
        }

        AnnotationModel.Tool tool = model.getTool();
        if (tool == AnnotationModel.Tool.DRAW) {
            previewStroke(gc);
        } else if (tool == AnnotationModel.Tool.RECTANGLE) {
            ShapeAnnotation.strokePreview(
                    gc,
                    ShapeAnnotation.Kind.RECTANGLE,
                    startX,
                    startY,
                    currentX,
                    currentY,
                    model.getColor(),
                    model.getStrokeWidth()
            );
        } else if (tool == AnnotationModel.Tool.ELLIPSE) {
            ShapeAnnotation.strokePreview(
                    gc,
                    ShapeAnnotation.Kind.ELLIPSE,
                    startX,
                    startY,
                    currentX,
                    currentY,
                    model.getColor(),
                    model.getStrokeWidth()
            );
        }
    }

    private void previewStroke(GraphicsContext gc) {
        if (currentPoints.size() >= 2) {
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
        } else if (currentPoints.size() == 1) {
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
        startX = currentX = event.getX();
        startY = currentY = event.getY();
        currentPoints.clear();
        if (model.getTool() == AnnotationModel.Tool.DRAW) {
            currentPoints.add(new StrokeAnnotation.Point(event.getX(), event.getY()));
        }
        redraw();
    }

    private void onDragged(MouseEvent event) {
        if (!drawing) {
            return;
        }
        currentX = event.getX();
        currentY = event.getY();
        if (model.getTool() == AnnotationModel.Tool.DRAW) {
            currentPoints.add(new StrokeAnnotation.Point(event.getX(), event.getY()));
        }
        redraw();
    }

    private void onReleased(MouseEvent event) {
        if (!drawing) {
            return;
        }
        drawing = false;
        currentX = event.getX();
        currentY = event.getY();

        switch (model.getTool()) {
            case DRAW -> commitStroke();
            case RECTANGLE -> commitShape(ShapeAnnotation.Kind.RECTANGLE);
            case ELLIPSE -> commitShape(ShapeAnnotation.Kind.ELLIPSE);
            case TEXT -> {
            }
        }
        currentPoints.clear();
        redraw();
    }

    private void commitStroke() {
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
    }

    private void commitShape(ShapeAnnotation.Kind kind) {
        if (Math.abs(currentX - startX) < MIN_SHAPE_SIZE && Math.abs(currentY - startY) < MIN_SHAPE_SIZE) {
            return;
        }
        long start = timeSupplier.getAsLong();
        long end = model.resolveEndTime(start, lengthSupplier.getAsLong());
        model.add(new ShapeAnnotation(
                start,
                end,
                kind,
                startX,
                startY,
                currentX,
                currentY,
                model.getColor(),
                model.getStrokeWidth()
        ));
    }

    private void placeText(double x, double y) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Texto");
        dialog.setHeaderText(null);
        dialog.initModality(Modality.WINDOW_MODAL);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setResizable(false);

        ButtonType ok = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);
        dialog.getDialogPane().setGraphic(null);
        dialog.getDialogPane().getStyleClass().add("text-annotation-dialog");
        dialog.getDialogPane().getStylesheets().add(
                Objects.requireNonNull(AnnotationCanvas.class.getResource("/styles.css")).toExternalForm()
        );

        TextField field = new TextField();
        field.setPromptText("Escribe aquí");
        field.setMaxWidth(Double.MAX_VALUE);

        int currentSize = (int) Math.round(model.getTextSize());
        Spinner<Integer> sizeSpinner = new Spinner<>(8, 96, Math.clamp(currentSize, 8, 96));
        sizeSpinner.setEditable(true);
        sizeSpinner.setPrefWidth(88);
        sizeSpinner.setMaxWidth(88);
        sizeSpinner.getEditor().setPrefColumnCount(3);

        Label sizeLabel = new Label("Tamaño");
        HBox sizeRow = new HBox(8, sizeLabel, sizeSpinner);
        sizeRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, field, sizeRow);
        content.setPadding(new Insets(4, 0, 0, 0));
        HBox.setHgrow(field, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(280);

        dialog.setOnShown(e -> {
            dialog.getDialogPane().getScene().setFill(Color.web("#12151c"));
            dialog.getDialogPane().getScene().getWindow().sizeToScene();
            field.requestFocus();
        });
        dialog.setResultConverter(button -> {
            if (button == null || button.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            commitSpinner(sizeSpinner);
            model.setTextSize(sizeSpinner.getValue());
            return field.getText();
        });

        Optional<String> result = dialog.showAndWait();
        result.map(String::trim).filter(s -> !s.isEmpty()).ifPresent(text -> {
            long start = timeSupplier.getAsLong();
            long end = model.resolveEndTime(start, lengthSupplier.getAsLong());
            model.add(new TextAnnotation(start, end, text, x, y, model.getColor(), model.getTextSize()));
            redraw();
        });
    }

    private static void commitSpinner(Spinner<Integer> spinner) {
        try {
            int parsed = Integer.parseInt(spinner.getEditor().getText().trim());
            spinner.getValueFactory().setValue(Math.clamp(parsed, 8, 96));
        } catch (NumberFormatException ignored) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }
}
