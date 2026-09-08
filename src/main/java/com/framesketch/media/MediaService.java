package com.framesketch.media;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.ImageView;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * VLCJ-backed media playback. Negative rates play a FFmpeg-generated reversed clip
 * so reverse motion stays smooth; {@link #timeMsProperty()} always reports original-timeline time.
 */
public class MediaService implements AutoCloseable {

    private final MediaPlayerFactory factory;
    private final EmbeddedMediaPlayer mediaPlayer;
    private final FfmpegReverseService reverseService;
    private final AtomicBoolean seeking = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();

    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final BooleanProperty mediaLoaded = new SimpleBooleanProperty(false);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);
    private final BooleanProperty preparingReverse = new SimpleBooleanProperty(false);
    /** Overlay spinner: only while user asked for reverse and cache is not ready yet. */
    private final BooleanProperty awaitingReverseOverlay = new SimpleBooleanProperty(false);
    private final AtomicBoolean switchingMedia = new AtomicBoolean(false);
    private final LongProperty timeMs = new SimpleLongProperty(0);
    private final LongProperty lengthMs = new SimpleLongProperty(0);
    private final DoubleProperty rate = new SimpleDoubleProperty(1.0);
    private final IntegerProperty volume = new SimpleIntegerProperty(100);
    private final StringProperty statusMessage = new SimpleStringProperty("");

    private Consumer<Void> onFinished = ignored -> {
    };

    private File originalFile;
    private Path reversedFile;
    private boolean usingReversedMedia;
    private boolean userWantsPlayback;
    private boolean pendingReverseActivation;
    private long originalLengthMs;

    public MediaService() {
        try {
            if (!new NativeDiscovery().discover()) {
                throw new IllegalStateException(missingVlcMessage());
            }
            factory = new MediaPlayerFactory();
            mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
            reverseService = new FfmpegReverseService();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException(missingVlcMessage() + "\nDetalle: " + ex.getMessage(), ex);
        }
        mediaPlayer.audio().setVolume(100);
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer player) {
                Platform.runLater(() -> {
                    playing.set(true);
                    statusMessage.set(usingReversedMedia
                            ? String.format("Reversa %.2fx", rate.get())
                            : "Reproduciendo");
                });
            }

            @Override
            public void paused(MediaPlayer player) {
                Platform.runLater(() -> {
                    if (!userWantsPlayback) {
                        playing.set(false);
                        statusMessage.set("Pausa");
                    }
                });
            }

            @Override
            public void stopped(MediaPlayer player) {
                if (switchingMedia.get()) {
                    return;
                }
                Platform.runLater(() -> {
                    if (!preparingReverse.get()) {
                        playing.set(false);
                        statusMessage.set("Detenido");
                    }
                });
            }

            @Override
            public void finished(MediaPlayer player) {
                if (switchingMedia.get()) {
                    return;
                }
                Platform.runLater(() -> {
                    playing.set(false);
                    userWantsPlayback = false;
                    if (usingReversedMedia) {
                        // End of reversed file == start of original timeline.
                        timeMs.set(0);
                        statusMessage.set("Inicio del video");
                    } else {
                        statusMessage.set("Finalizado");
                        onFinished.accept(null);
                    }
                });
            }

            @Override
            public void timeChanged(MediaPlayer player, long newTime) {
                if (seeking.get()) {
                    return;
                }
                Platform.runLater(() -> timeMs.set(toOriginalTime(newTime)));
            }

            @Override
            public void lengthChanged(MediaPlayer player, long newLength) {
                Platform.runLater(() -> {
                    if (!usingReversedMedia && newLength > 0) {
                        originalLengthMs = newLength;
                        lengthMs.set(newLength);
                    } else if (usingReversedMedia && originalLengthMs <= 0 && newLength > 0) {
                        originalLengthMs = newLength;
                        lengthMs.set(newLength);
                    } else if (originalLengthMs > 0) {
                        lengthMs.set(originalLengthMs);
                    } else if (newLength > 0) {
                        lengthMs.set(newLength);
                    }
                });
            }

            @Override
            public void error(MediaPlayer player) {
                Platform.runLater(() -> {
                    playing.set(false);
                    userWantsPlayback = false;
                    preparingReverse.set(false);
                    statusMessage.set("Error al reproducir el archivo");
                });
            }
        });
    }

    public void attachVideoSurface(ImageView imageView) {
        Objects.requireNonNull(imageView, "imageView");
        mediaPlayer.videoSurface().set(new ImageViewVideoSurface(imageView));
    }

    public void setOnFinished(Consumer<Void> onFinished) {
        this.onFinished = onFinished != null ? onFinished : ignored -> {
        };
    }

    public boolean open(File file) {
        if (file == null || !file.isFile()) {
            statusMessage.set("Archivo inválido");
            return false;
        }
        reverseService.cancelCurrent();
        preparingReverse.set(false);
        awaitingReverseOverlay.set(false);
        pendingReverseActivation = false;
        switchingMedia.set(false);
        long openGen = generation.incrementAndGet();
        originalFile = file;
        reversedFile = null;
        usingReversedMedia = false;
        originalLengthMs = 0;
        userWantsPlayback = true;

        boolean ok = mediaPlayer.media().play(file.getAbsolutePath());
        mediaLoaded.set(ok);
        if (ok) {
            double desired = rate.get() < 0 ? 1.0 : rate.get();
            rate.set(desired);
            mediaPlayer.controls().setRate((float) Math.abs(desired));
            mediaPlayer.audio().setVolume(volume.get());
            mediaPlayer.audio().setMute(muted.get());
            statusMessage.set("Cargado: " + file.getName());
            // Prefetch reverse in background; forward playback continues.
            startReversePrefetch(openGen);
        } else {
            userWantsPlayback = false;
            statusMessage.set("No se pudo abrir: " + file.getName());
        }
        return ok;
    }

    public void play() {
        userWantsPlayback = true;
        if (rate.get() < 0) {
            requestReversedPlayback(true);
            return;
        }
        switchToOriginalIfNeeded(timeMs.get(), true);
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
        mediaPlayer.controls().play();
        playing.set(true);
        statusMessage.set("Reproduciendo");
    }

    public void pause() {
        userWantsPlayback = false;
        pendingReverseActivation = false;
        awaitingReverseOverlay.set(false);
        mediaPlayer.controls().pause();
        playing.set(false);
        statusMessage.set("Pausa");
    }

    public void togglePlayPause() {
        if (playing.get()) {
            pause();
        } else {
            play();
        }
    }

    public void stop() {
        userWantsPlayback = false;
        pendingReverseActivation = false;
        awaitingReverseOverlay.set(false);
        // Keep reverse prefetch running so cache is ready; only cancel on new open.
        switchToOriginalIfNeeded(0, false);
        mediaPlayer.controls().stop();
        seek(0);
        playing.set(false);
        timeMs.set(0);
        statusMessage.set("Detenido");
    }

    public void rewindToStart() {
        seek(0);
        statusMessage.set("Inicio del video");
    }

    public void seek(long originalMillis) {
        long length = Math.max(originalLengthMs, lengthMs.get());
        long clamped = Math.max(0, length > 0 ? Math.min(originalMillis, length) : originalMillis);
        seeking.set(true);
        long mediaTime = toMediaTime(clamped);
        mediaPlayer.controls().setTime(mediaTime);
        timeMs.set(clamped);
        seeking.set(false);
    }

    /**
     * Applies playback rate immediately. Negative values use the cached FFmpeg-reversed clip.
     * Timeline stays in original time: media seeks to {@code length - t}.
     */
    public void setRate(double newRate) {
        double clamped = sanitizeRate(newRate);
        double previous = rate.get();
        rate.set(clamped);

        boolean resume = userWantsPlayback || playing.get();
        if (clamped < 0) {
            if (previous >= 0 || !usingReversedMedia) {
                requestReversedPlayback(resume);
            } else {
                mediaPlayer.controls().setRate((float) Math.abs(clamped));
                if (resume) {
                    userWantsPlayback = true;
                    mediaPlayer.controls().play();
                    playing.set(true);
                }
                statusMessage.set(String.format("Reversa %.2fx", clamped));
            }
            return;
        }

        pendingReverseActivation = false;
        awaitingReverseOverlay.set(false);
        long at = timeMs.get();
        switchToOriginalIfNeeded(at, resume);
        applyPositiveRate(clamped, resume);
    }

    /**
     * Starts (or reuses) reverse-cache generation without interrupting forward playback.
     */
    private void startReversePrefetch(long requestId) {
        if (originalFile == null) {
            return;
        }
        if (FfmpegReverseService.findFfmpeg().isEmpty()) {
            statusMessage.set("FFmpeg no encontrado: la reversa suave no estará disponible.");
            return;
        }

        try {
            if (reverseService.hasValidCache(originalFile.toPath())) {
                reversedFile = reverseService.cacheFileFor(originalFile.toPath());
                preparingReverse.set(false);
                awaitingReverseOverlay.set(false);
                statusMessage.set("Reversa en caché lista");
                if (pendingReverseActivation || rate.get() < 0) {
                    activateReversed(reversedFile, timeMs.get(), userWantsPlayback || playing.get());
                    pendingReverseActivation = false;
                }
                return;
            }
        } catch (IOException ignored) {
            // Build below.
        }

        preparingReverse.set(true);
        // Overlay stays hidden unless the user already requested reverse.
        statusMessage.set("Preparando reversa en segundo plano...");

        reverseService.prepareAsync(
                originalFile.toPath(),
                msg -> Platform.runLater(() -> {
                    if (requestId == generation.get() && preparingReverse.get()) {
                        statusMessage.set(msg);
                    }
                }),
                result -> Platform.runLater(() -> {
                    if (requestId != generation.get()) {
                        return;
                    }
                    preparingReverse.set(false);
                    reversedFile = result.reversedFile();
                    if (pendingReverseActivation || rate.get() < 0) {
                        boolean resume = pendingReverseActivation || userWantsPlayback || playing.get();
                        pendingReverseActivation = false;
                        awaitingReverseOverlay.set(false);
                        activateReversed(reversedFile, timeMs.get(), resume);
                    } else {
                        awaitingReverseOverlay.set(false);
                        statusMessage.set("Reversa en caché lista");
                    }
                }),
                error -> Platform.runLater(() -> {
                    if (requestId != generation.get()) {
                        return;
                    }
                    preparingReverse.set(false);
                    pendingReverseActivation = false;
                    awaitingReverseOverlay.set(false);
                    statusMessage.set("No se pudo generar reversa: " + error.getMessage());
                    if (rate.get() < 0) {
                        rate.set(Math.abs(rate.get()));
                    }
                })
        );
    }

    private void requestReversedPlayback(boolean resumePlayback) {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (FfmpegReverseService.findFfmpeg().isEmpty()) {
            statusMessage.set("FFmpeg no encontrado. Instálalo para reversa suave (brew install ffmpeg).");
            rate.set(Math.abs(rate.get()));
            return;
        }

        userWantsPlayback = resumePlayback;

        if (reversedFile != null) {
            try {
                if (reverseService.hasValidCache(originalFile.toPath())) {
                    awaitingReverseOverlay.set(false);
                    activateReversed(reversedFile, timeMs.get(), resumePlayback);
                    return;
                }
            } catch (IOException ignored) {
                // Fall through.
            }
        }

        // Not ready yet: keep forward playback, show overlay only now.
        pendingReverseActivation = true;
        awaitingReverseOverlay.set(true);
        if (!preparingReverse.get()) {
            startReversePrefetch(generation.get());
        }
        statusMessage.set("Reversa en preparación… se activará al terminar");
    }

    private void activateReversed(Path reversed, long originalTime, boolean resumePlayback) {
        awaitingReverseOverlay.set(false);
        pendingReverseActivation = false;
        loadMediaAtOriginalTime(reversed.toAbsolutePath().toString(), originalTime, true, resumePlayback);
        if (rate.get() >= 0) {
            rate.set(-1.0);
        }
        double abs = Math.abs(rate.get());
        mediaPlayer.controls().setRate((float) abs);
        if (resumePlayback) {
            statusMessage.set(String.format("Reversa %.2fx", rate.get()));
        } else {
            statusMessage.set("Reversa lista (pausa)");
        }
    }

    private void switchToOriginalIfNeeded(long originalTime, boolean resumePlayback) {
        if (!usingReversedMedia) {
            if (resumePlayback) {
                mediaPlayer.controls().play();
                playing.set(true);
            }
            return;
        }
        if (originalFile == null) {
            return;
        }
        loadMediaAtOriginalTime(originalFile.getAbsolutePath(), originalTime, false, resumePlayback);
        statusMessage.set(resumePlayback ? "Reproduciendo" : "Pausa");
    }

    /**
     * Switches MRL while ignoring spurious finished/stopped events, and seeks using original timeline.
     */
    private void loadMediaAtOriginalTime(
            String mrl,
            long originalTime,
            boolean reversed,
            boolean resumePlayback
    ) {
        boolean previousReversed = usingReversedMedia;
        switchingMedia.set(true);
        usingReversedMedia = reversed;
        long length = Math.max(originalLengthMs, lengthMs.get());
        long clamped = Math.max(0, length > 0 ? Math.min(originalTime, length) : originalTime);
        double startSeconds = reversed
                ? Math.max(0, (length - clamped) / 1000.0)
                : clamped / 1000.0;

        boolean ok = mediaPlayer.media().play(mrl, ":start-time=" + startSeconds);
        if (!ok) {
            usingReversedMedia = previousReversed;
            switchingMedia.set(false);
            statusMessage.set(reversed
                    ? "No se pudo abrir el video invertido"
                    : "No se pudo volver al video original");
            return;
        }

        mediaPlayer.audio().setVolume(volume.get());
        mediaPlayer.audio().setMute(muted.get());
        seeking.set(true);
        timeMs.set(clamped);
        mediaPlayer.controls().setTime(toMediaTime(clamped));
        seeking.set(false);

        if (resumePlayback) {
            userWantsPlayback = true;
            mediaPlayer.controls().play();
            playing.set(true);
        } else {
            userWantsPlayback = false;
            mediaPlayer.controls().pause();
            playing.set(false);
        }

        Platform.runLater(() -> {
            switchingMedia.set(false);
            seeking.set(true);
            mediaPlayer.controls().setTime(toMediaTime(timeMs.get()));
            seeking.set(false);
        });
    }

    private void applyPositiveRate(double positiveRate, boolean resumePlayback) {
        double clamped = Math.abs(sanitizeRate(positiveRate));
        rate.set(clamped);
        mediaPlayer.controls().setRate((float) clamped);
        if (resumePlayback) {
            userWantsPlayback = true;
            if (!mediaPlayer.status().isPlaying()) {
                mediaPlayer.controls().play();
            }
            playing.set(true);
        }
        statusMessage.set(String.format("Velocidad: %.2fx", clamped));
    }

    private long toOriginalTime(long mediaTime) {
        if (!usingReversedMedia) {
            return mediaTime;
        }
        long length = Math.max(originalLengthMs, lengthMs.get());
        if (length <= 0) {
            return mediaTime;
        }
        return Math.max(0, length - mediaTime);
    }

    private long toMediaTime(long originalTime) {
        if (!usingReversedMedia) {
            return originalTime;
        }
        long length = Math.max(originalLengthMs, lengthMs.get());
        if (length <= 0) {
            return originalTime;
        }
        return Math.max(0, length - originalTime);
    }

    private static double sanitizeRate(double value) {
        double clamped = Math.max(-2.0, Math.min(2.0, value));
        if (Math.abs(clamped) < 0.05) {
            return Math.copySign(0.05, clamped >= 0 ? 1 : -1);
        }
        return clamped;
    }

    public void setVolume(int value) {
        int clamped = Math.max(0, Math.min(200, value));
        volume.set(clamped);
        mediaPlayer.audio().setVolume(clamped);
        if (clamped > 0 && muted.get()) {
            setMuted(false);
        }
    }

    public void setMuted(boolean value) {
        muted.set(value);
        mediaPlayer.audio().setMute(value);
        statusMessage.set(value ? "Mute" : "Sonido activado");
    }

    public void toggleMute() {
        setMuted(!muted.get());
    }

    public BooleanProperty playingProperty() {
        return playing;
    }

    public BooleanProperty mediaLoadedProperty() {
        return mediaLoaded;
    }

    public BooleanProperty mutedProperty() {
        return muted;
    }

    public BooleanProperty preparingReverseProperty() {
        return preparingReverse;
    }

    public BooleanProperty awaitingReverseOverlayProperty() {
        return awaitingReverseOverlay;
    }

    public LongProperty timeMsProperty() {
        return timeMs;
    }

    public LongProperty lengthMsProperty() {
        return lengthMs;
    }

    public DoubleProperty rateProperty() {
        return rate;
    }

    public IntegerProperty volumeProperty() {
        return volume;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public long getTimeMs() {
        return timeMs.get();
    }

    public boolean isPlaying() {
        return playing.get();
    }

    @Override
    public void close() {
        userWantsPlayback = false;
        reverseService.cancelCurrent();
        preparingReverse.set(false);
        awaitingReverseOverlay.set(false);
        mediaPlayer.controls().stop();
        mediaPlayer.release();
        factory.release();
        reverseService.close();
    }

    private static String missingVlcMessage() {
        return """
                No se pudo cargar libVLC.
                Instala VLC desde https://www.videolan.org/ (misma arquitectura que el JDK: arm64 o x64)
                y vuelve a abrir FrameSketch.""";
    }
}
