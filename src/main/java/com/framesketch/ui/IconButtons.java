package com.framesketch.ui;

import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

final class IconButtons {

    private IconButtons() {
    }

    static Button iconButton(Ikon ikon, String tooltip, Runnable action) {
        FontIcon icon = FontIcon.of(ikon, 18);
        icon.getStyleClass().add("control-icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    static ToggleButton iconToggleButton(Ikon ikon, String tooltip) {
        FontIcon icon = FontIcon.of(ikon, 18);
        icon.getStyleClass().add("control-icon");
        ToggleButton button = new ToggleButton();
        button.setGraphic(icon);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        return button;
    }

    static FontIcon icon(Ikon ikon, int size) {
        FontIcon fontIcon = FontIcon.of(ikon, size);
        fontIcon.getStyleClass().add("control-icon");
        return fontIcon;
    }
}
