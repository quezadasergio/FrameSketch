package com.framesketch.timeline;

import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public class TimelineModel {

    public static final class Keyframe {
        private final String id = UUID.randomUUID().toString();
        private final LongProperty timeMs = new SimpleLongProperty();

        public Keyframe(long timeMs) {
            this.timeMs.set(timeMs);
        }

        public String getId() {
            return id;
        }

        public LongProperty timeMsProperty() {
            return timeMs;
        }

        public long getTimeMs() {
            return timeMs.get();
        }

        public void setTimeMs(long value) {
            timeMs.set(value);
        }
    }

    private final ObservableList<Keyframe> keyframes = FXCollections.observableArrayList();
    private final ObjectProperty<Keyframe> selectedKeyframe = new SimpleObjectProperty<>();
    private final LongProperty markerAMs = new SimpleLongProperty(-1);
    private final LongProperty markerBMs = new SimpleLongProperty(-1);

    public ObservableList<Keyframe> getKeyframes() {
        return keyframes;
    }

    public ObjectProperty<Keyframe> selectedKeyframeProperty() {
        return selectedKeyframe;
    }

    public Keyframe getSelectedKeyframe() {
        return selectedKeyframe.get();
    }

    public void setSelectedKeyframe(Keyframe keyframe) {
        selectedKeyframe.set(keyframe);
    }

    public LongProperty markerAMsProperty() {
        return markerAMs;
    }

    public LongProperty markerBMsProperty() {
        return markerBMs;
    }

    public long getMarkerAMs() {
        return markerAMs.get();
    }

    public long getMarkerBMs() {
        return markerBMs.get();
    }

    public boolean hasMarkerA() {
        return markerAMs.get() >= 0;
    }

    public boolean hasMarkerB() {
        return markerBMs.get() >= 0;
    }

    public void addKeyframe(long timeMs) {
        Keyframe kf = new Keyframe(Math.max(0, timeMs));
        keyframes.add(kf);
        selectedKeyframe.set(kf);
        sortKeyframes();
    }

    public void removeKeyframe(Keyframe keyframe) {
        if (keyframe == null) {
            return;
        }
        keyframes.remove(keyframe);
        if (keyframe.equals(selectedKeyframe.get())) {
            selectedKeyframe.set(null);
        }
    }

    public boolean removeSelectedKeyframe() {
        Keyframe selected = selectedKeyframe.get();
        if (selected == null) {
            return false;
        }
        removeKeyframe(selected);
        return true;
    }

    public void setMarkerA(long timeMs) {
        markerAMs.set(Math.max(0, timeMs));
    }

    public void setMarkerB(long timeMs) {
        markerBMs.set(Math.max(0, timeMs));
    }

    public void clearMarkers() {
        markerAMs.set(-1);
        markerBMs.set(-1);
    }

    public void clearAll() {
        keyframes.clear();
        selectedKeyframe.set(null);
        clearMarkers();
    }

    public Optional<Long> deltaMs() {
        if (!hasMarkerA() || !hasMarkerB()) {
            return Optional.empty();
        }
        return Optional.of(Math.abs(markerBMs.get() - markerAMs.get()));
    }

    public Optional<Keyframe> previousKeyframe(long fromTimeMs) {
        return keyframes.stream()
                .sorted(Comparator.comparingLong(Keyframe::getTimeMs).reversed())
                .filter(kf -> kf.getTimeMs() < fromTimeMs - 1)
                .findFirst();
    }

    public Optional<Keyframe> nextKeyframe(long fromTimeMs) {
        return keyframes.stream()
                .sorted(Comparator.comparingLong(Keyframe::getTimeMs))
                .filter(kf -> kf.getTimeMs() > fromTimeMs + 1)
                .findFirst();
    }

    public void sortKeyframes() {
        FXCollections.sort(keyframes, Comparator.comparingLong(Keyframe::getTimeMs));
    }
}
