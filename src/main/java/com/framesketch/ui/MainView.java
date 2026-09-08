package com.framesketch.ui;

import com.framesketch.annotation.AnnotationCanvas;
import com.framesketch.annotation.AnnotationModel;
import com.framesketch.media.MediaService;
import com.framesketch.playlist.PlaylistModel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;

public class MainView {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final PlaylistModel playlist = new PlaylistModel();
    private final AnnotationModel annotationModel = new AnnotationModel();

    private MediaService mediaService;
    private AnnotationCanvas annotationCanvas;
    private ImageView videoView;
    private PlayerControls playerControls;

    public MainView(Stage stage) {
        this.stage = stage;
        root.getStyleClass().add("root-pane");
    }

    public BorderPane getRoot() {
        return root;
    }

    public boolean initializeMedia() {
        try {
            mediaService = new MediaService();
        } catch (IllegalStateException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
            alert.setTitle("VLC requerido");
            alert.setHeaderText("No se pudo iniciar el motor de video");
            alert.setResizable(true);
            alert.getDialogPane().setPrefWidth(520);
            alert.showAndWait();
            return false;
        }

        videoView = new ImageView();
        videoView.setPreserveRatio(true);
        videoView.setSmooth(true);

        annotationCanvas = new AnnotationCanvas(
                annotationModel,
                mediaService::getTimeMs,
                () -> mediaService.lengthMsProperty().get()
        );
        annotationCanvas.setMouseTransparent(false);

        StackPane videoStack = new StackPane(videoView, annotationCanvas);
        videoStack.getStyleClass().add("video-stack");
        videoStack.setMinSize(320, 180);
        BorderPane.setMargin(videoStack, new Insets(8, 0, 0, 0));

        ProgressIndicator reverseSpinner = new ProgressIndicator();
        reverseSpinner.setMaxSize(36, 36);
        reverseSpinner.getStyleClass().add("reverse-spinner");
        reverseSpinner.setMouseTransparent(true);
        reverseSpinner.visibleProperty().bind(mediaService.awaitingReverseOverlayProperty());
        reverseSpinner.managedProperty().bind(mediaService.awaitingReverseOverlayProperty());

        Label reverseHint = new Label("Preparando reversa…");
        reverseHint.getStyleClass().add("reverse-spinner-label");
        reverseHint.setMouseTransparent(true);
        reverseHint.visibleProperty().bind(mediaService.awaitingReverseOverlayProperty());
        reverseHint.managedProperty().bind(mediaService.awaitingReverseOverlayProperty());

        VBox reverseBadge = new VBox(4, reverseSpinner, reverseHint);
        reverseBadge.setAlignment(Pos.CENTER);
        reverseBadge.getStyleClass().add("reverse-spinner-badge");
        reverseBadge.setMouseTransparent(true);
        reverseBadge.visibleProperty().bind(mediaService.awaitingReverseOverlayProperty());
        reverseBadge.managedProperty().bind(mediaService.awaitingReverseOverlayProperty());
        StackPane.setAlignment(reverseBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(reverseBadge, new Insets(0, 14, 14, 0));
        videoStack.getChildren().add(reverseBadge);

        videoStack.layoutBoundsProperty().addListener((obs, o, bounds) -> fitVideoSurface());
        videoView.fitWidthProperty().bind(videoStack.widthProperty());
        videoView.fitHeightProperty().bind(videoStack.heightProperty());
        annotationCanvas.widthProperty().bind(videoStack.widthProperty());
        annotationCanvas.heightProperty().bind(videoStack.heightProperty());

        mediaService.attachVideoSurface(videoView);
        mediaService.timeMsProperty().addListener((obs, o, n) -> annotationCanvas.redraw());

        mediaService.setOnFinished(ignored ->
                playlist.advanceAfterFinished().ifPresentOrElse(
                        this::playFile,
                        () -> mediaService.statusMessageProperty().set("Lista finalizada")
                )
        );

        AnnotationToolbar toolbar = new AnnotationToolbar(
                annotationModel,
                () -> {
                    annotationModel.undo();
                    annotationCanvas.redraw();
                },
                () -> {
                    annotationModel.clear();
                    annotationCanvas.redraw();
                }
        );

        PlaylistPanel playlistPanel = new PlaylistPanel(playlist, stage, this::playFile);
        playerControls = new PlayerControls(mediaService);

        Label brand = new Label("FrameSketch");
        brand.getStyleClass().add("brand");
        BorderPane top = new BorderPane();
        top.setLeft(brand);
        top.setPadding(new Insets(10, 14, 4, 14));
        BorderPane.setAlignment(brand, Pos.CENTER_LEFT);

        root.setTop(top);
        root.setCenter(videoStack);
        root.setLeft(toolbar);
        root.setRight(playlistPanel);
        root.setBottom(playerControls);

        stage.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                installShortcuts(newScene);
            }
        });
        if (stage.getScene() != null) {
            installShortcuts(stage.getScene());
        }

        return true;
    }

    private void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcut);
    }

    private void handleShortcut(KeyEvent event) {
        if (playerControls == null) {
            return;
        }
        if (event.getTarget() instanceof javafx.scene.control.TextInputControl
                || playerControls.isTypingInDistanceField()) {
            return;
        }

        KeyCode code = event.getCode();
        boolean handled = true;
        switch (code) {
            case Z -> mediaService.togglePlayPause();
            case X -> mediaService.stop();
            case C -> mediaService.rewindToStart();
            case V -> playerControls.setRateFromUi(-0.25);
            case B -> playerControls.setRateFromUi(0.25);
            case N -> playerControls.setRateFromUi(1.0);
            case M -> playerControls.setRateFromUi(-1.0);
            case A -> playerControls.jumpToPreviousKeyframe();
            case S -> playerControls.jumpToNextKeyframe();
            case K -> playerControls.addKeyframeAtPlayhead();
            case DIGIT1, NUMPAD1 -> playerControls.placeMarkerA();
            case DIGIT2, NUMPAD2 -> playerControls.placeMarkerB();
            case DELETE, BACK_SPACE -> playerControls.deleteSelectedKeyframe();
            default -> handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    private void fitVideoSurface() {
        if (annotationCanvas != null) {
            Platform.runLater(annotationCanvas::redraw);
        }
    }

    private void playFile(File file) {
        if (file == null) {
            return;
        }
        annotationModel.clear();
        if (playerControls != null) {
            playerControls.getTimeline().clearAll();
        }
        if (annotationCanvas != null) {
            annotationCanvas.redraw();
        }
        if (mediaService.open(file)) {
            mediaService.play();
        }
    }

    public void dispose() {
        if (mediaService != null) {
            mediaService.close();
        }
    }
}
