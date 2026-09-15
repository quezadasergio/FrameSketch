package com.framesketch.app;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Rounded transparent splash shown before the main window.
 */
public final class SplashScreen {

    private static final double SIZE = 420;
    private static final double CORNER_RADIUS = 92;

    private SplashScreen() {
    }

    public static void show(Stage ownerHint, Consumer<Void> onFinished) {
        Stage splash = new Stage(StageStyle.TRANSPARENT);
        splash.setAlwaysOnTop(true);
        splash.setResizable(false);
        splash.setTitle("FrameSketch");

        Image image = new Image(
                Objects.requireNonNull(SplashScreen.class.getResourceAsStream("/splash.png"))
        );
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(SIZE);
        imageView.setFitHeight(SIZE);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        Label credit = new Label("Application made by quezadasergio");
        credit.getStyleClass().add("splash-credit");
        credit.setMouseTransparent(true);
        StackPane.setAlignment(credit, Pos.BOTTOM_CENTER);
        credit.setTranslateY(-28);

        StackPane root = new StackPane(imageView, credit);
        root.setPrefSize(SIZE, SIZE);
        root.getStyleClass().add("splash-root");

        Rectangle clip = new Rectangle(SIZE, SIZE);
        clip.setArcWidth(CORNER_RADIUS);
        clip.setArcHeight(CORNER_RADIUS);
        root.setClip(clip);

        Scene scene = new Scene(root, SIZE, SIZE, Color.TRANSPARENT);
        scene.getStylesheets().add(
                Objects.requireNonNull(SplashScreen.class.getResource("/styles.css")).toExternalForm()
        );
        splash.setScene(scene);
        splash.getIcons().add(image);

        if (ownerHint != null && ownerHint.getIcons().isEmpty()) {
            ownerHint.getIcons().add(image);
        }

        splash.centerOnScreen();
        splash.show();

        PauseTransition delay = new PauseTransition(Duration.seconds(5));
        delay.setOnFinished(e -> {
            splash.close();
            onFinished.accept(null);
        });
        delay.play();
    }
}
