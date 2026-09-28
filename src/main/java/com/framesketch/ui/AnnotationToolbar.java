package com.framesketch.ui;

import com.framesketch.annotation.AnnotationModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

public class AnnotationToolbar extends VBox {

    private static final Color[] PALETTE = {
            Color.web("#f8fafc"),
            Color.web("#ef4444"),
            Color.web("#f97316"),
            Color.web("#eab308"),
            Color.web("#22c55e"),
            Color.web("#14b8a6"),
            Color.web("#3b82f6"),
            Color.web("#8b5cf6"),
            Color.web("#ec4899"),
            Color.web("#0f172a")
    };

    public AnnotationToolbar(AnnotationModel model, Runnable onUndo, Runnable onClear) {
        getStyleClass().add("annotation-toolbar");
        setSpacing(10);
        setPadding(new Insets(12));
        setPrefWidth(220);
        setMinWidth(200);

        Label title = new Label("Análisis");
        title.getStyleClass().add("section-title");

        ToggleGroup tools = new ToggleGroup();
        ToggleButton drawBtn = toolButton(
                MaterialDesignP.PENCIL,
                "Dibujar a mano alzada",
                AnnotationModel.Tool.DRAW,
                model,
                tools
        );
        ToggleButton rectBtn = toolButton(
                MaterialDesignS.SQUARE_OUTLINE,
                "Rectángulo",
                AnnotationModel.Tool.RECTANGLE,
                model,
                tools
        );
        ToggleButton ellipseBtn = toolButton(
                MaterialDesignC.CIRCLE_OUTLINE,
                "Elipse",
                AnnotationModel.Tool.ELLIPSE,
                model,
                tools
        );
        ToggleButton textBtn = toolButton(
                MaterialDesignF.FORM_TEXTBOX,
                "Texto",
                AnnotationModel.Tool.TEXT,
                model,
                tools
        );
        drawBtn.setSelected(true);

        tools.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null) {
                old.setSelected(true);
            }
        });

        HBox toolRow = new HBox(6, drawBtn, rectBtn, ellipseBtn, textBtn);
        makeEqualSquares(drawBtn, rectBtn, ellipseBtn, textBtn);

        Label colorLabel = new Label("Color");
        GridPane colorGrid = buildVerticalColorPalette(model);

        ColorPicker colorPicker = new ColorPicker(model.getColor());
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.valueProperty().bindBidirectional(model.colorProperty());
        colorPicker.setTooltip(new Tooltip("Más colores y personalizado (RGB / HSB)"));
        Label customColorHint = new Label("Personalizado (RGB)");
        customColorHint.getStyleClass().add("hint");

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

        Button undoBtn = IconButtons.iconButton(
                MaterialDesignU.UNDO,
                "Deshacer el último trazo o texto (W)",
                onUndo
        );
        Button clearBtn = IconButtons.iconButton(
                MaterialDesignD.DELETE_SWEEP,
                "Elimina todas las anotaciones de inmediato (Q)",
                onClear
        );
        clearBtn.getStyleClass().add("danger");

        HBox actionRow = new HBox(6, undoBtn, clearBtn);
        matchSquareSize(drawBtn, undoBtn, clearBtn);

        Label hint = new Label("Las anotaciones quedan ligadas al tiempo del video en el que se crean.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                title,
                toolRow,
                colorLabel,
                colorGrid,
                customColorHint,
                colorPicker,
                widthLabel,
                widthSlider,
                widthValue,
                jitterBox,
                jitterLabel,
                jitterSlider,
                actionRow,
                spacer,
                hint
        );
        setAlignment(Pos.TOP_LEFT);
    }

    private static ToggleButton toolButton(
            Ikon ikon,
            String tooltip,
            AnnotationModel.Tool tool,
            AnnotationModel model,
            ToggleGroup group
    ) {
        ToggleButton button = IconButtons.iconToggleButton(ikon, tooltip);
        button.setToggleGroup(group);
        button.setOnAction(e -> model.setTool(tool));
        return button;
    }

    private static void makeEqualSquares(Region... nodes) {
        for (Region node : nodes) {
            node.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(node, Priority.ALWAYS);
            node.widthProperty().addListener((obs, o, n) -> {
                double size = n.doubleValue();
                node.setMinHeight(size);
                node.setPrefHeight(size);
                node.setMaxHeight(size);
            });
        }
    }

    private static void matchSquareSize(Region source, Region... targets) {
        source.widthProperty().addListener((obs, o, n) -> {
            double size = n.doubleValue();
            for (Region target : targets) {
                target.setMinSize(size, size);
                target.setPrefSize(size, size);
                target.setMaxSize(size, size);
            }
        });
    }

    private static GridPane buildVerticalColorPalette(AnnotationModel model) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("color-palette-grid");
        grid.setHgap(6);
        grid.setVgap(6);
        ColumnConstraints col = new ColumnConstraints();
        col.setHgrow(Priority.ALWAYS);
        col.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col, col);

        ToggleGroup group = new ToggleGroup();
        for (int i = 0; i < PALETTE.length; i++) {
            Color color = PALETTE[i];
            ToggleButton swatch = new ToggleButton();
            swatch.getStyleClass().add("color-swatch");
            swatch.setToggleGroup(group);
            swatch.setMaxWidth(Double.MAX_VALUE);
            swatch.setPrefHeight(28);
            swatch.setMinHeight(28);
            Rectangle fill = new Rectangle(18, 18, color);
            fill.setArcWidth(4);
            fill.setArcHeight(4);
            swatch.setGraphic(fill);
            swatch.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            swatch.setTooltip(new Tooltip(toHex(color)));
            swatch.setOnAction(e -> model.colorProperty().set(color));
            if (colorsClose(model.getColor(), color)) {
                swatch.setSelected(true);
            }
            grid.add(swatch, i % 2, i / 2);
        }

        model.colorProperty().addListener((obs, o, n) -> {
            for (var node : grid.getChildren()) {
                if (node instanceof ToggleButton button
                        && button.getGraphic() instanceof Rectangle rect
                        && rect.getFill() instanceof Color swatchColor
                        && colorsClose(n, swatchColor)) {
                    button.setSelected(true);
                    return;
                }
            }
            group.selectToggle(null);
        });

        return grid;
    }

    private static boolean colorsClose(Color a, Color b) {
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a.getRed() - b.getRed()) < 0.02
                && Math.abs(a.getGreen() - b.getGreen()) < 0.02
                && Math.abs(a.getBlue() - b.getBlue()) < 0.02
                && Math.abs(a.getOpacity() - b.getOpacity()) < 0.02;
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
