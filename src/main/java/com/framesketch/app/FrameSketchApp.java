package com.framesketch.app;

import com.framesketch.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Objects;

public class FrameSketchApp extends Application {

    private MainView mainView;

    @Override
    public void start(Stage stage) {
        Image icon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/splash.png")));
        stage.getIcons().add(icon);

        SplashScreen.show(stage, ignored -> openMainWindow(stage));
    }

    private void openMainWindow(Stage stage) {
        mainView = new MainView(stage);
        Scene scene = new Scene(mainView.getRoot(), 1280, 800);
        scene.getStylesheets().add(
                Objects.requireNonNull(FrameSketchApp.class.getResource("/styles.css")).toExternalForm()
        );
        stage.setTitle("FrameSketch");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.show();

        if (!mainView.initializeMedia()) {
            stage.close();
        }
    }

    @Override
    public void stop() {
        if (mainView != null) {
            mainView.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
