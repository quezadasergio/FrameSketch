package com.framesketch.playlist;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.Optional;

public class PlaylistModel {

    private final ObservableList<File> items = FXCollections.observableArrayList();
    private final IntegerProperty currentIndex = new SimpleIntegerProperty(-1);
    private final ObjectProperty<PlayMode> playMode = new SimpleObjectProperty<>(PlayMode.SEQUENCE);

    public ObservableList<File> getItems() {
        return items;
    }

    public IntegerProperty currentIndexProperty() {
        return currentIndex;
    }

    public int getCurrentIndex() {
        return currentIndex.get();
    }

    public void setCurrentIndex(int index) {
        currentIndex.set(index);
    }

    public ObjectProperty<PlayMode> playModeProperty() {
        return playMode;
    }

    public PlayMode getPlayMode() {
        return playMode.get();
    }

    public void setPlayMode(PlayMode mode) {
        playMode.set(mode);
    }

    public void addFiles(Iterable<File> files) {
        for (File file : files) {
            if (file != null && file.isFile()) {
                items.add(file);
            }
        }
        if (currentIndex.get() < 0 && !items.isEmpty()) {
            currentIndex.set(0);
        }
    }

    public void removeSelected(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        items.remove(index);
        int current = currentIndex.get();
        if (items.isEmpty()) {
            currentIndex.set(-1);
        } else if (index < current) {
            currentIndex.set(current - 1);
        } else if (index == current) {
            currentIndex.set(Math.min(current, items.size() - 1));
        }
    }

    public void moveUp(int index) {
        if (index <= 0 || index >= items.size()) {
            return;
        }
        File item = items.remove(index);
        items.add(index - 1, item);
        int current = currentIndex.get();
        if (current == index) {
            currentIndex.set(index - 1);
        } else if (current == index - 1) {
            currentIndex.set(index);
        }
    }

    public void moveDown(int index) {
        if (index < 0 || index >= items.size() - 1) {
            return;
        }
        File item = items.remove(index);
        items.add(index + 1, item);
        int current = currentIndex.get();
        if (current == index) {
            currentIndex.set(index + 1);
        } else if (current == index + 1) {
            currentIndex.set(index);
        }
    }

    public Optional<File> currentFile() {
        int index = currentIndex.get();
        if (index < 0 || index >= items.size()) {
            return Optional.empty();
        }
        return Optional.of(items.get(index));
    }

    /**
     * Advances playlist according to the active play mode after a media finished event.
     *
     * @return next file to play, or empty if playback should stop
     */
    public Optional<File> advanceAfterFinished() {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.max(0, currentIndex.get());
        return switch (getPlayMode()) {
            case SINGLE -> Optional.empty();
            case REPEAT_ONE -> {
                currentIndex.set(index);
                yield Optional.of(items.get(index));
            }
            case SEQUENCE -> {
                if (index >= items.size() - 1) {
                    yield Optional.empty();
                }
                int next = index + 1;
                currentIndex.set(next);
                yield Optional.of(items.get(next));
            }
            case REPEAT_ALL -> {
                int next = (index + 1) % items.size();
                currentIndex.set(next);
                yield Optional.of(items.get(next));
            }
        };
    }
}
