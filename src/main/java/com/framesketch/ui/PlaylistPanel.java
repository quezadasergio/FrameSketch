package com.framesketch.ui;

import com.framesketch.playlist.PlayMode;
import com.framesketch.playlist.PlaylistModel;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class PlaylistPanel extends VBox {

    private final PlaylistModel playlist;
    private final ListView<File> listView;

    public PlaylistPanel(PlaylistModel playlist, Window owner, Consumer<File> onPlayRequest) {
        this.playlist = playlist;
        getStyleClass().add("playlist-panel");
        setSpacing(10);
        setPadding(new Insets(12));
        setPrefWidth(260);
        setMinWidth(220);

        Label title = new Label("Lista de reproducción");
        title.getStyleClass().add("section-title");

        listView = new ListView<>(playlist.getItems());
        listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().remove("current-track");
                } else {
                    setText(item.getName());
                    int index = getIndex();
                    if (index == playlist.getCurrentIndex()) {
                        if (!getStyleClass().contains("current-track")) {
                            getStyleClass().add("current-track");
                        }
                    } else {
                        getStyleClass().remove("current-track");
                    }
                }
            }
        });
        playlist.currentIndexProperty().addListener((obs, o, n) -> listView.refresh());
        VBox.setVgrow(listView, Priority.ALWAYS);

        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                int index = listView.getSelectionModel().getSelectedIndex();
                if (index >= 0) {
                    playlist.setCurrentIndex(index);
                    onPlayRequest.accept(playlist.getItems().get(index));
                }
            }
        });

        Button addBtn = IconButtons.iconButton(
                MaterialDesignF.FOLDER_OPEN,
                "Abrir / añadir videos",
                () -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("Añadir videos");
                    chooser.getExtensionFilters().addAll(
                            new FileChooser.ExtensionFilter(
                                    "Video",
                                    "*.mp4", "*.mkv", "*.avi", "*.mov", "*.webm", "*.wmv", "*.flv", "*.m4v", "*.mpg", "*.mpeg"
                            ),
                            new FileChooser.ExtensionFilter("Todos", "*.*")
                    );
                    List<File> files = chooser.showOpenMultipleDialog(owner);
                    if (files != null && !files.isEmpty()) {
                        boolean wasEmpty = playlist.getItems().isEmpty();
                        playlist.addFiles(files);
                        if (wasEmpty) {
                            playlist.currentFile().ifPresent(onPlayRequest);
                        }
                    }
                }
        );

        Button removeBtn = IconButtons.iconButton(
                MaterialDesignD.DELETE,
                "Quitar video seleccionado",
                () -> playlist.removeSelected(listView.getSelectionModel().getSelectedIndex())
        );

        Button upBtn = IconButtons.iconButton(
                MaterialDesignA.ARROW_UP_BOLD,
                "Subir en la lista",
                () -> playlist.moveUp(listView.getSelectionModel().getSelectedIndex())
        );

        Button downBtn = IconButtons.iconButton(
                MaterialDesignA.ARROW_DOWN_BOLD,
                "Bajar en la lista",
                () -> playlist.moveDown(listView.getSelectionModel().getSelectedIndex())
        );

        Button playSelected = new Button("Reproducir");
        playSelected.setGraphic(IconButtons.icon(MaterialDesignP.PLAY, 16));
        playSelected.getStyleClass().add("icon-button");
        playSelected.setMaxWidth(Double.MAX_VALUE);
        playSelected.setTooltip(new javafx.scene.control.Tooltip("Reproducir seleccionado"));
        playSelected.setOnAction(e -> {
            int index = listView.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                playlist.setCurrentIndex(index);
                onPlayRequest.accept(playlist.getItems().get(index));
            }
        });

        Label modeLabel = new Label("Modo");
        ComboBox<PlayMode> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(PlayMode.values());
        modeBox.valueProperty().bindBidirectional(playlist.playModeProperty());
        modeBox.setMaxWidth(Double.MAX_VALUE);

        HBox buttons = new HBox(6, addBtn, removeBtn, upBtn, downBtn);
        getChildren().addAll(title, listView, buttons, playSelected, modeLabel, modeBox);
    }
}
