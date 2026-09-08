package com.framesketch.ui;

import com.framesketch.annotation.AnnotationModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class AnnotationToolbar extends VBox {

    public AnnotationToolbar(AnnotationModel model, Runnable onUndo, Runnable onClear) {
        getStyleClass().add("annotation-toolbar");
        setSpacing(10);
        setPadding(new Insets(12));
        setPrefWidth(220);
        setMinWidth(200);

        Label title = new Label("Análisis");
        title.getStyleClass().add("section-title");

        ToggleGroup tools = new ToggleGroup();
        ToggleButton drawBtn = new ToggleButton("Dibujar");
        ToggleButton textBtn = new ToggleButton("Texto");
        drawBtn.setToggleGroup(tools);
        textBtn.setToggleGroup(tools);
        drawBtn.setSelected(true);
        drawBtn.setMaxWidth(Double.MAX_VALUE);
        textBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(drawBtn, Priority.ALWAYS);
        HBox.setHgrow(textBtn, Priority.ALWAYS);

        drawBtn.setOnAction(e -> model.setTool(AnnotationModel.Tool.DRAW));
        textBtn.setOnAction(e -> model.setTool(AnnotationModel.Tool.TEXT));
        tools.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null) {
                old.setSelected(true);
            }
        });

        HBox toolRow = new HBox(8, drawBtn, textBtn);

        Label colorLabel = new Label("Color");
        ColorPicker colorPicker = new ColorPicker(model.getColor());
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.valueProperty().bindBidirectional(model.colorProperty());

        Label widthLabel = new Label("Grosor");
        Slider widthSlider = new Slider(1, 24, model.getStrokeWidth());
        widthSlider.setShowTickMarks(true);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(5);
        widthSlider.setBlockIncrement(1);
        model.strokeWidthProperty().bindBidirectional(widthSlider.valueProperty());
        Label widthValue = new Label();
        widthValue.textProperty().bind(widthSlider.valueProperty().map(v -> String.format("%.0f px", v.doubleValue())));

        CheckBox jitterBox = new CheckBox("Líneas con jitter");
        jitterBox.selectedProperty().bindBidirectional(model.jitterEnabledProperty());
        jitterBox.setTooltip(new Tooltip("Aplica temblor a los trazos nuevos"));

        Label jitterLabel = new Label("Amplitud jitter");
        Slider jitterSlider = new Slider(0.5, 8, model.getJitterAmplitude());
        model.jitterAmplitudeProperty().bindBidirectional(jitterSlider.valueProperty());
        jitterSlider.disableProperty().bind(jitterBox.selectedProperty().not());

        Button undoBtn = new Button("Deshacer");
        undoBtn.setMaxWidth(Double.MAX_VALUE);
        undoBtn.setOnAction(e -> onUndo.run());
        undoBtn.setTooltip(new Tooltip("Deshacer el último trazo o texto"));

        Button clearBtn = new Button("Quitar todo");
        clearBtn.getStyleClass().add("danger");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setOnAction(e -> onClear.run());
        clearBtn.setTooltip(new Tooltip("Elimina todas las anotaciones de inmediato"));

        Label hint = new Label("Las anotaciones quedan ligadas al tiempo del video en el que se crean.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                title,
                toolRow,
                colorLabel,
                colorPicker,
                widthLabel,
                widthSlider,
                widthValue,
                jitterBox,
                jitterLabel,
                jitterSlider,
                undoBtn,
                clearBtn,
                spacer,
                hint
        );
        setAlignment(Pos.TOP_LEFT);
    }
}
