package com.framesketch.ui;

import com.framesketch.media.MediaService;
import com.framesketch.timeline.TimelineModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

public class PlayerControls extends VBox {

    private final MediaService media;
    private final TimelineModel timeline = new TimelineModel();
    private final Slider speedSlider;
    private final FontIcon playPauseIcon;
    private final FontIcon muteIcon;
    private final Label deltaLabel;
    private final Label speedResultLabel;
    private final TextField distanceField;
    private boolean updatingSpeedSlider;

    public PlayerControls(MediaService media) {
        this.media = media;
        getStyleClass().add("player-controls");
        setSpacing(8);
        setPadding(new Insets(10, 14, 12, 14));

        Label timeLabel = new Label("00:00 / 00:00");
        timeLabel.getStyleClass().add("time-label");
        timeLabel.setMinWidth(110);

        media.timeMsProperty().addListener((obs, o, n) ->
                timeLabel.setText(formatTime(n.longValue()) + " / " + formatTime(media.lengthMsProperty().get()))
        );
        media.lengthMsProperty().addListener((obs, o, n) ->
                timeLabel.setText(formatTime(media.getTimeMs()) + " / " + formatTime(n.longValue()))
        );

        TimelineView timelineView = new TimelineView(media, timeline);
        HBox.setHgrow(timelineView, Priority.ALWAYS);

        HBox timelineRow = new HBox(10, timelineView, timeLabel);
        timelineRow.setAlignment(Pos.CENTER_LEFT);

        playPauseIcon = IconButtons.icon(MaterialDesignP.PLAY, 18);
        Button playPause = new Button();
        playPause.setGraphic(playPauseIcon);
        playPause.getStyleClass().add("icon-button");
        playPause.setTooltip(new Tooltip("Play / Pausa (Z)"));
        playPause.setFocusTraversable(false);
        playPause.setOnAction(e -> media.togglePlayPause());
        media.playingProperty().addListener((obs, o, playing) ->
                playPauseIcon.setIconCode(playing ? MaterialDesignP.PAUSE : MaterialDesignP.PLAY)
        );

        Button stop = IconButtons.iconButton(MaterialDesignS.STOP, "Stop (X)", media::stop);
        Button rewind = IconButtons.iconButton(MaterialDesignR.REPLAY, "Ir al inicio (C)", media::rewindToStart);
        Button prevKf = IconButtons.iconButton(
                MaterialDesignC.CHEVRON_LEFT,
                "Keyframe anterior (A)",
                this::jumpToPreviousKeyframe
        );
        Button nextKf = IconButtons.iconButton(
                MaterialDesignC.CHEVRON_RIGHT,
                "Keyframe siguiente (S)",
                this::jumpToNextKeyframe
        );
        Button addKf = IconButtons.iconButton(
                MaterialDesignF.FLAG_PLUS,
                "Añadir keyframe (K). También: Shift+clic o doble clic en la timeline.",
                this::addKeyframeAtPlayhead
        );
        Button markerA = IconButtons.iconButton(
                MaterialDesignA.ALPHA_A_CIRCLE,
                "Colocar marcador A (1)",
                () -> timeline.setMarkerA(media.getTimeMs())
        );
        Button markerB = IconButtons.iconButton(
                MaterialDesignA.ALPHA_B_CIRCLE,
                "Colocar marcador B (2)",
                () -> timeline.setMarkerB(media.getTimeMs())
        );
        Button slowBack = IconButtons.iconButton(
                MaterialDesignR.REWIND,
                "Cámara lenta atrás 0.25x (V)",
                () -> setRateFromUi(-0.25)
        );
        Button slowFwd = IconButtons.iconButton(
                MaterialDesignF.FAST_FORWARD,
                "Cámara lenta adelante 0.25x (B)",
                () -> setRateFromUi(0.25)
        );
        Button normal = IconButtons.iconButton(
                MaterialDesignP.PLAY_SPEED,
                "Velocidad normal 1x (N)",
                () -> setRateFromUi(1.0)
        );
        Button reverse = IconButtons.iconButton(
                MaterialDesignR.REWIND_OUTLINE,
                "Reproducción atrás (M) — usa clip invertido en caché (se prepara al abrir el video)",
                () -> setRateFromUi(-1.0)
        );

        HBox transport = new HBox(
                6,
                prevKf, playPause, stop, rewind, nextKf,
                addKf, markerA, markerB,
                slowBack, slowFwd, normal, reverse
        );
        transport.setAlignment(Pos.CENTER_LEFT);

        Label speedLabel = new Label("Velocidad");
        speedSlider = new Slider(-1.0, 1.0, 1.0);
        speedSlider.setMajorTickUnit(0.25);
        speedSlider.setMinorTickCount(0);
        speedSlider.setShowTickMarks(true);
        speedSlider.setShowTickLabels(true);
        speedSlider.setBlockIncrement(0.05);
        speedSlider.setPrefWidth(200);
        speedSlider.setTooltip(new Tooltip("Negativo = reversa suave vía FFmpeg (caché). Cambio inmediato."));

        Label speedValue = new Label("1.00x");
        speedValue.setMinWidth(52);

        Runnable applySpeed = () -> {
            if (updatingSpeedSlider) {
                return;
            }
            double rate = sanitizeRate(speedSlider.getValue());
            media.setRate(rate);
            speedValue.setText(String.format("%.2fx", rate));
        };
        speedSlider.valueProperty().addListener((obs, o, n) -> applySpeed.run());
        speedSlider.valueChangingProperty().addListener((obs, was, changing) -> {
            // Apply continuously while dragging and once more when released.
            applySpeed.run();
        });

        muteIcon = IconButtons.icon(MaterialDesignV.VOLUME_HIGH, 18);
        Button muteBtn = new Button();
        muteBtn.setGraphic(muteIcon);
        muteBtn.getStyleClass().add("icon-button");
        muteBtn.setTooltip(new Tooltip("Mute / Unmute"));
        muteBtn.setFocusTraversable(false);
        muteBtn.setOnAction(e -> media.toggleMute());
        media.mutedProperty().addListener((obs, o, muted) ->
                muteIcon.setIconCode(muted ? MaterialDesignV.VOLUME_OFF : MaterialDesignV.VOLUME_HIGH)
        );

        Slider volumeSlider = new Slider(0, 200, 100);
        volumeSlider.setPrefWidth(120);
        volumeSlider.setTooltip(new Tooltip("Volumen"));
        volumeSlider.valueProperty().addListener((obs, o, n) -> media.setVolume(n.intValue()));
        media.volumeProperty().addListener((obs, o, n) -> {
            if (Math.abs(volumeSlider.getValue() - n.doubleValue()) > 0.5) {
                volumeSlider.setValue(n.doubleValue());
            }
        });

        Label status = new Label();
        status.getStyleClass().add("status-label");
        status.textProperty().bind(media.statusMessageProperty());

        HBox speedRow = new HBox(8, speedLabel, speedSlider, speedValue, muteBtn, volumeSlider, status);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        deltaLabel = new Label("Δt: —");
        deltaLabel.getStyleClass().add("metric-label");
        distanceField = new TextField();
        distanceField.setPromptText("Distancia");
        distanceField.setPrefWidth(90);
        distanceField.setTooltip(new Tooltip("Distancia recorrida entre A y B (misma unidad que uses para la velocidad)"));
        Label unitHint = new Label("→ v:");
        speedResultLabel = new Label("—");
        speedResultLabel.getStyleClass().add("metric-label");
        speedResultLabel.setMinWidth(80);

        Runnable recalc = this::recalculateSpeed;
        distanceField.textProperty().addListener((obs, o, n) -> recalc.run());
        timeline.markerAMsProperty().addListener((obs, o, n) -> recalc.run());
        timeline.markerBMsProperty().addListener((obs, o, n) -> recalc.run());

        HBox metricsRow = new HBox(10, deltaLabel, new Label("Distancia"), distanceField, unitHint, speedResultLabel);
        metricsRow.setAlignment(Pos.CENTER_LEFT);
        metricsRow.getStyleClass().add("metrics-row");

        getChildren().addAll(timelineRow, transport, speedRow, metricsRow);
    }

    public TimelineModel getTimeline() {
        return timeline;
    }

    public boolean isTypingInDistanceField() {
        return distanceField.isFocused();
    }

    public void addKeyframeAtPlayhead() {
        timeline.addKeyframe(media.getTimeMs());
    }

    public void jumpToPreviousKeyframe() {
        timeline.previousKeyframe(media.getTimeMs()).ifPresent(kf -> {
            timeline.setSelectedKeyframe(kf);
            media.seek(kf.getTimeMs());
        });
    }

    public void jumpToNextKeyframe() {
        timeline.nextKeyframe(media.getTimeMs()).ifPresent(kf -> {
            timeline.setSelectedKeyframe(kf);
            media.seek(kf.getTimeMs());
        });
    }

    public void placeMarkerA() {
        timeline.setMarkerA(media.getTimeMs());
    }

    public void placeMarkerB() {
        timeline.setMarkerB(media.getTimeMs());
    }

    public void setRateFromUi(double rate) {
        double sanitized = sanitizeRate(rate);
        updatingSpeedSlider = true;
        speedSlider.setValue(Math.max(-1.0, Math.min(1.0, sanitized)));
        updatingSpeedSlider = false;
        media.setRate(sanitized);
    }

    public void deleteSelectedKeyframe() {
        timeline.removeSelectedKeyframe();
    }

    private void recalculateSpeed() {
        timeline.deltaMs().ifPresentOrElse(delta -> {
            double seconds = delta / 1000.0;
            deltaLabel.setText(String.format("Δt: %s (%.3f s)", formatTime(delta), seconds));
            try {
                String raw = distanceField.getText().trim().replace(',', '.');
                if (raw.isEmpty() || seconds <= 0) {
                    speedResultLabel.setText("—");
                    return;
                }
                double distance = Double.parseDouble(raw);
                double velocity = distance / seconds;
                speedResultLabel.setText(String.format("%.3f u/s", velocity));
            } catch (NumberFormatException ex) {
                speedResultLabel.setText("dist. inválida");
            }
        }, () -> {
            deltaLabel.setText("Δt: —");
            speedResultLabel.setText("—");
        });
    }

    private static double sanitizeRate(double rate) {
        if (Math.abs(rate) < 0.05) {
            return Math.copySign(0.05, rate >= 0 ? 1 : -1);
        }
        return rate;
    }

    private static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        long millis = ms % 1000;
        if (h > 0) {
            return String.format("%d:%02d:%02d.%03d", h, m, s, millis);
        }
        return String.format("%02d:%02d.%03d", m, s, millis);
    }
}
