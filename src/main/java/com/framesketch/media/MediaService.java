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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * VLCJ-backed playback with FFmpeg reversed-clip cache for smooth reverse.
 * Direction switches (M/N) share one time anchor; repeating the same mode is a no-op.
 */
public class MediaService implements AutoCloseable {

    private final MediaPlayerFactory factory;
    private final EmbeddedMediaPlayer mediaPlayer;
    private final FfmpegProxyService proxyService;
    private final AtomicBoolean seeking = new AtomicBoolean(false);
    private final AtomicBoolean switchingMedia = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();

    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final BooleanProperty mediaLoaded = new SimpleBooleanProperty(false);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);
    private final BooleanProperty preparingReverse = new SimpleBooleanProperty(false);
    private final BooleanProperty reverseBadgeVisible = new SimpleBooleanProperty(false);
    private final StringProperty reverseBadgeText = new SimpleStringProperty("Preparando reversa…");
    private final LongProperty timeMs = new SimpleLongProperty(0);
    private final LongProperty lengthMs = new SimpleLongProperty(0);
    private final DoubleProperty rate = new SimpleDoubleProperty(1.0);
    private final IntegerProperty volume = new SimpleIntegerProperty(100);
    private final StringProperty statusMessage = new SimpleStringProperty("");
    private final IntegerProperty playlistInfoEpoch = new SimpleIntegerProperty(0);
    private long lastPlaylistInfoBumpMs;

    private Consumer<Void> onFinished = ignored -> {
    };

    private File originalFile;
    private Path reversedFile;
    private boolean usingReversedMedia;
    private boolean userWantsPlayback;
    private boolean pendingReverseActivation;
    private long originalLengthMs;
    /** Shared anchor updated only when switching direction (M↔N or slow V↔B). */
    private long directionSwitchAnchorMs = -1;

    public MediaService() {
        try {
            if (!new NativeDiscovery().discover()) {
                throw new IllegalStateException(missingVlcMessage());
            }
            factory = new MediaPlayerFactory();
            mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
            proxyService = new FfmpegProxyService();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException(missingVlcMessage() + "\nDetalle: " + ex.getMessage(), ex);
        }

        if (FfmpegProxyService.findFfmpeg().isEmpty()) {
            statusMessage.set("FFmpeg no encontrado: conversión y reversa suave no estarán disponibles.");
        }

        mediaPlayer.audio().setVolume(100);
        wirePlayerEvents();
        wireProxyListeners();
    }

    private void wirePlayerEvents() {
        mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer player) {
                Platform.runLater(() -> {
                    playing.set(true);
                    statusMessage.set(usingReversedMedia
                            ? String.format("Reversa %.2fx", rate.get())
                            : String.format("Velocidad: %.2fx", Math.abs(rate.get())));
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
                if (seeking.get() || switchingMedia.get()) {
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
                        if (originalFile != null) {
                            proxyService.rememberDurationMs(originalFile.toPath(), newLength);
                        }
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

    private void wireProxyListeners() {
        proxyService.setReverseProgressListener(progress -> Platform.runLater(() -> {
            String name = progress.source().getFileName().toString();
            showReverseBadge(name + "  " + progress.timeLabel());
            if (originalFile != null
                    && originalFile.toPath().toAbsolutePath().normalize().equals(progress.source())) {
                preparingReverse.set(true);
            }
            bumpPlaylistInfo();
        }));

        proxyService.setReverseCompletedListener(result -> Platform.runLater(() -> {
            if (result.kind() != FfmpegProxyService.ProxyKind.REVERSE) {
                return;
            }
            Path source = result.source().toAbsolutePath().normalize();
            boolean isCurrent = originalFile != null
                    && originalFile.toPath().toAbsolutePath().normalize().equals(source);

            if (isCurrent) {
                reversedFile = result.proxyFile();
                preparingReverse.set(false);
                if (pendingReverseActivation) {
                    boolean resume = userWantsPlayback || playing.get();
                    pendingReverseActivation = false;
                    hideReverseBadge();
                    long anchor = directionSwitchAnchorMs >= 0
                            ? directionSwitchAnchorMs
                            : capturePlayerPlaybackMs();
                    activateReversed(anchor, resume);
                } else if (proxyService.activeReverseSource() == null) {
                    hideReverseBadge();
                }
            } else if (proxyService.activeReverseSource() == null) {
                hideReverseBadge();
                preparingReverse.set(false);
            }
            bumpPlaylistInfo(true);
        }));

        proxyService.setReverseFailedListener(error -> Platform.runLater(() -> {
            preparingReverse.set(false);
            if (pendingReverseActivation) {
                pendingReverseActivation = false;
                if (rate.get() < 0) {
                    rate.set(Math.abs(rate.get()));
                }
                statusMessage.set("No se pudo generar reversa: " + error.getMessage());
            }
            if (proxyService.activeReverseSource() == null) {
                hideReverseBadge();
            }
            bumpPlaylistInfo(true);
        }));

        proxyService.setStatusChangedListener(ignored -> Platform.runLater(this::bumpPlaylistInfo));
    }

    public void attachVideoSurface(ImageView imageView) {
        Objects.requireNonNull(imageView, "imageView");
        mediaPlayer.videoSurface().set(new ImageViewVideoSurface(imageView));
    }

    public void setOnFinished(Consumer<Void> onFinished) {
        this.onFinished = onFinished != null ? onFinished : ignored -> {
        };
    }

    public void enqueueReverseForFiles(Iterable<File> files) {
        if (FfmpegProxyService.findFfmpeg().isEmpty()) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            if (file != null && file.isFile()) {
                paths.add(file.toPath());
            }
        }
        if (!paths.isEmpty()) {
            proxyService.probeDurationsAsync(paths);
            proxyService.enqueueReverseAll(paths);
            showReverseBadge("Cola de reversa…");
            bumpPlaylistInfo();
        }
    }

    public String playlistTooltipFor(File file) {
        if (file == null) {
            return "";
        }
        FfmpegProxyService.FileStatus status = proxyService.statusFor(file.toPath());
        String durationLine = status.durationMs().isPresent()
                ? "Duración: " + formatClock(status.durationMs().getAsLong())
                : "Duración: …";

        String reverseLine = switch (status.reverseStatus()) {
            case READY -> "Reversa: lista";
            case RUNNING -> "Reversa: "
                    + status.reverseProgressTime().orElse("time=00:00:00.00")
                    + (status.durationMs().isPresent()
                    ? " / " + formatClock(status.durationMs().getAsLong())
                    : "");
            case QUEUED -> "Reversa: en cola";
            case FAILED -> "Reversa: error";
            case PENDING -> "Reversa: pendiente";
        };
        return durationLine + "\n" + reverseLine;
    }

    public IntegerProperty playlistInfoEpochProperty() {
        return playlistInfoEpoch;
    }

    public boolean open(File file) {
        if (file == null || !file.isFile()) {
            statusMessage.set("Archivo inválido");
            return false;
        }
        pendingReverseActivation = false;
        usingReversedMedia = false;
        reversedFile = null;
        originalLengthMs = 0;
        directionSwitchAnchorMs = -1;
        long openGen = generation.incrementAndGet();

        originalFile = file;
        userWantsPlayback = true;
        mediaLoaded.set(false);
        timeMs.set(0);
        lengthMs.set(0);

        if (rate.get() < 0) {
            rate.set(1.0);
        }

        try {
            if (proxyService.hasValidCache(file.toPath(), FfmpegProxyService.ProxyKind.REVERSE)) {
                reversedFile = proxyService.cacheFileFor(file.toPath(), FfmpegProxyService.ProxyKind.REVERSE);
                preparingReverse.set(false);
                hideReverseBadge();
            } else {
                preparingReverse.set(true);
                showReverseBadge("Preparando reversa…");
                proxyService.enqueueReverse(file.toPath(), true);
            }
        } catch (IOException ex) {
            preparingReverse.set(false);
        }

        boolean ok = mediaPlayer.media().play(file.getAbsolutePath());
        mediaLoaded.set(ok);
        if (ok) {
            mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
            mediaPlayer.audio().setVolume(volume.get());
            mediaPlayer.audio().setMute(muted.get());
            statusMessage.set("Cargado: " + file.getName());
            bumpPlaylistInfo(true);
        } else {
            userWantsPlayback = false;
            statusMessage.set("No se pudo abrir: " + file.getName());
        }
        return ok;
    }

    public void play() {
        userWantsPlayback = true;
        if (usingReversedMedia || rate.get() < 0) {
            if (!usingReversedMedia) {
                switchToReversePlayback();
                return;
            }
            mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
            mediaPlayer.controls().play();
            playing.set(true);
            return;
        }
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
        mediaPlayer.controls().play();
        playing.set(true);
        statusMessage.set(String.format("Velocidad: %.2fx", Math.abs(rate.get())));
    }

    public void pause() {
        userWantsPlayback = false;
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
        if (usingReversedMedia) {
            switchToOriginalKeepingTime(0, false);
        } else {
            mediaPlayer.controls().stop();
            seek(0);
        }
        playing.set(false);
        timeMs.set(0);
        statusMessage.set("Detenido");
    }

    public void rewindToStart() {
        seek(0);
        statusMessage.set("Inicio del video");
    }

    public void seek(long originalMillis) {
        long length = originalTimelineLength();
        long clamped = Math.max(0, length > 0 ? Math.min(originalMillis, length) : originalMillis);
        seeking.set(true);
        timeMs.set(clamped);
        mediaPlayer.controls().setTime(toMediaTime(clamped));
        seeking.set(false);
    }

    public void switchToReversePlayback() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (usingReversedMedia || pendingReverseActivation) {
            return;
        }
        long savedMs = capturePlayerPlaybackMs();
        directionSwitchAnchorMs = savedMs;
        timeMs.set(savedMs);
        rate.set(-1.0);
        userWantsPlayback = true;
        requestReversedPlayback(true, savedMs);
    }

    public void switchToForwardPlayback() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (!usingReversedMedia) {
            if (pendingReverseActivation) {
                pendingReverseActivation = false;
                if (rate.get() < 0) {
                    rate.set(1.0);
                }
                if (!preparingReverse.get()) {
                    hideReverseBadge();
                }
            }
            return;
        }
        long savedMs = capturePlayerPlaybackMs();
        directionSwitchAnchorMs = savedMs;
        timeMs.set(savedMs);
        pendingReverseActivation = false;
        rate.set(1.0);
        userWantsPlayback = true;
        switchToOriginalKeepingTime(savedMs, true);
    }

    public void setSlowReverse() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (usingReversedMedia || pendingReverseActivation) {
            setRate(-0.25);
            return;
        }
        long savedMs = capturePlayerPlaybackMs();
        directionSwitchAnchorMs = savedMs;
        timeMs.set(savedMs);
        rate.set(-0.25);
        userWantsPlayback = true;
        requestReversedPlayback(true, savedMs);
    }

    public void setSlowForward() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (!usingReversedMedia) {
            if (pendingReverseActivation) {
                pendingReverseActivation = false;
                if (!preparingReverse.get()) {
                    hideReverseBadge();
                }
            }
            setRate(0.25);
            return;
        }
        long savedMs = capturePlayerPlaybackMs();
        directionSwitchAnchorMs = savedMs;
        timeMs.set(savedMs);
        pendingReverseActivation = false;
        rate.set(0.25);
        userWantsPlayback = true;
        switchToOriginalKeepingTime(savedMs, true);
    }

    public boolean isUsingReversedMedia() {
        return usingReversedMedia;
    }

    /**
     * Speed within current direction; always resumes playback. Does not switch files.
     */
    public void setRate(double newRate) {
        double magnitude = Math.abs(sanitizeRate(newRate));
        userWantsPlayback = true;
        if (usingReversedMedia) {
            rate.set(-magnitude);
            mediaPlayer.controls().setRate((float) magnitude);
            mediaPlayer.controls().play();
            playing.set(true);
            statusMessage.set(String.format("Reversa %.2fx", -magnitude));
            return;
        }
        rate.set(magnitude);
        mediaPlayer.controls().setRate((float) magnitude);
        mediaPlayer.controls().play();
        playing.set(true);
        statusMessage.set(String.format("Velocidad: %.2fx", magnitude));
    }

    private long capturePlayerPlaybackMs() {
        mediaPlayer.controls().pause();
        playing.set(false);
        long mediaTime = mediaPlayer.status().time();
        if (mediaTime < 0) {
            mediaTime = 0;
        }
        long original = toOriginalTime(mediaTime);
        return clampToOriginalTimeline(original);
    }

    private void requestReversedPlayback(boolean resumePlayback, long atOriginalTime) {
        if (FfmpegProxyService.findFfmpeg().isEmpty()) {
            statusMessage.set("FFmpeg no encontrado. Instálalo para reversa suave (brew install ffmpeg).");
            rate.set(Math.abs(rate.get()));
            return;
        }
        userWantsPlayback = resumePlayback;
        directionSwitchAnchorMs = atOriginalTime;
        timeMs.set(atOriginalTime);

        try {
            if (proxyService.hasValidCache(originalFile.toPath(), FfmpegProxyService.ProxyKind.REVERSE)) {
                reversedFile = proxyService.cacheFileFor(originalFile.toPath(), FfmpegProxyService.ProxyKind.REVERSE);
                hideReverseBadge();
                activateReversed(atOriginalTime, resumePlayback);
                return;
            }
        } catch (IOException ignored) {
            // Fall through.
        }

        pendingReverseActivation = true;
        preparingReverse.set(true);
        showReverseBadge("Preparando reversa…");
        proxyService.enqueueReverse(originalFile.toPath(), true);
    }

    private void activateReversed(long originalTime, boolean resumePlayback) {
        if (reversedFile == null) {
            return;
        }
        pendingReverseActivation = false;
        if (rate.get() >= 0) {
            rate.set(-1.0);
        }
        loadMediaAtOriginalTime(
                reversedFile.toAbsolutePath().toString(),
                originalTime,
                true,
                resumePlayback
        );
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
        if (resumePlayback) {
            statusMessage.set(String.format("Reversa %.2fx", rate.get()));
        } else {
            statusMessage.set("Reversa lista (pausa)");
        }
    }

    private void switchToOriginalKeepingTime(long originalTime, boolean resumePlayback) {
        if (originalFile == null) {
            return;
        }
        if (rate.get() < 0) {
            rate.set(1.0);
        }
        loadMediaAtOriginalTime(
                originalFile.getAbsolutePath(),
                originalTime,
                false,
                resumePlayback
        );
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
        statusMessage.set(resumePlayback
                ? String.format("Velocidad: %.2fx", Math.abs(rate.get()))
                : "Pausa");
    }

    private void loadMediaAtOriginalTime(
            String mrl,
            long originalTime,
            boolean reversed,
            boolean resumePlayback
    ) {
        boolean previousReversed = usingReversedMedia;
        switchingMedia.set(true);
        usingReversedMedia = reversed;
        long length = originalTimelineLength();
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
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));

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
            mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
            seeking.set(false);
        });
    }

    private long toOriginalTime(long mediaTime) {
        if (!usingReversedMedia) {
            return mediaTime;
        }
        long orig = originalTimelineLength();
        if (orig <= 0) {
            return mediaTime;
        }
        return Math.max(0, orig - Math.max(0, mediaTime));
    }

    private long toMediaTime(long originalTime) {
        if (!usingReversedMedia) {
            return originalTime;
        }
        long orig = originalTimelineLength();
        if (orig <= 0) {
            return originalTime;
        }
        return Math.max(0, orig - Math.max(0, Math.min(originalTime, orig)));
    }

    private long originalTimelineLength() {
        return Math.max(originalLengthMs, lengthMs.get());
    }

    private long clampToOriginalTimeline(long originalMs) {
        long length = originalTimelineLength();
        if (length > 0) {
            return Math.max(0, Math.min(originalMs, length));
        }
        return Math.max(0, originalMs);
    }

    private static double sanitizeRate(double value) {
        double clamped = Math.max(-2.0, Math.min(2.0, value));
        if (Math.abs(clamped) < 0.05) {
            return Math.copySign(0.05, clamped >= 0 ? 1 : -1);
        }
        return clamped;
    }

    private void showReverseBadge(String text) {
        reverseBadgeText.set(text != null && !text.isBlank() ? text : "Preparando reversa…");
        reverseBadgeVisible.set(true);
    }

    private void hideReverseBadge() {
        reverseBadgeVisible.set(false);
    }

    private void bumpPlaylistInfo() {
        bumpPlaylistInfo(false);
    }

    private void bumpPlaylistInfo(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPlaylistInfoBumpMs < 250) {
            return;
        }
        lastPlaylistInfoBumpMs = now;
        playlistInfoEpoch.set(playlistInfoEpoch.get() + 1);
    }

    private static String formatClock(long ms) {
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

    public BooleanProperty reverseBadgeVisibleProperty() {
        return reverseBadgeVisible;
    }

    public StringProperty reverseBadgeTextProperty() {
        return reverseBadgeText;
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

    public void clearCacheForFile(File file) {
        if (file == null) {
            return;
        }
        proxyService.clearCacheFor(file.toPath());
        try {
            if (reversedFile != null
                    && originalFile != null
                    && originalFile.toPath().toAbsolutePath().normalize()
                    .equals(file.toPath().toAbsolutePath().normalize())) {
                reversedFile = null;
            }
        } catch (Exception ignored) {
            reversedFile = null;
        }
        bumpPlaylistInfo(true);
    }

    @Override
    public void close() {
        userWantsPlayback = false;
        preparingReverse.set(false);
        hideReverseBadge();
        mediaPlayer.controls().stop();
        mediaPlayer.release();
        factory.release();
        proxyService.close();
    }

    private static String missingVlcMessage() {
        return """
                No se pudo cargar libVLC.
                Instala VLC desde https://www.videolan.org/ (misma arquitectura que el JDK: arm64 o x64)
                y vuelve a abrir FrameSketch.""";
    }
}
