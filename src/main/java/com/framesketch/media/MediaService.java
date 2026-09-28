package com.framesketch.media;

import javafx.animation.PauseTransition;
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
import javafx.util.Duration;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * VLCJ playback with an FFmpeg reversed-clip cache.
 * A single player is used so Video Toolbox is not shared by two decoders.
 * Direction changes pause, load the other file, then play when VLC is ready.
 */
public class MediaService implements AutoCloseable {

    public static final double SLOW_MOTION_RATE = 0.5;
    private static final double ZERO_RATE_EPS = 0.03;

    private final MediaPlayerFactory factory;
    private final EmbeddedMediaPlayer mediaPlayer;
    private final FfmpegProxyService proxyService;
    private final AtomicBoolean seeking = new AtomicBoolean(false);
    private final AtomicBoolean switchingMedia = new AtomicBoolean(false);
    private final AtomicBoolean awaitingReady = new AtomicBoolean(false);
    private final AtomicBoolean holdPlayhead = new AtomicBoolean(false);

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
    private Path forwardFile;
    private boolean usingReversedMedia;
    private boolean usingForwardProxy;
    private boolean userWantsPlayback;
    private boolean pendingReverseActivation;
    private boolean pendingResumeAfterReady;
    private boolean pendingReadyReversed;
    private long originalLengthMs;
    private long directionSwitchAnchorMs = -1;
    private long pendingReadyOriginalTime;
    private double lastNonZeroRate = 1.0;
    private int recoverAttempts;
    private PauseTransition recoverTimer;

    public MediaService() {
        try {
            if (!new NativeDiscovery().discover()) {
                throw new IllegalStateException(missingVlcMessage());
            }
            factory = new MediaPlayerFactory(
                    "--no-osd",
                    "--file-caching=250"
            );
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
                recoverAttempts = 0;
                maybeFinishLoad();
                Platform.runLater(() -> {
                    playing.set(true);
                    statusMessage.set(statusForRate());
                });
            }

            @Override
            public void paused(MediaPlayer player) {
                Platform.runLater(() -> {
                    if (!userWantsPlayback && !awaitingReady.get()) {
                        playing.set(false);
                        statusMessage.set("Pausa");
                    }
                });
            }

            @Override
            public void stopped(MediaPlayer player) {
                if (switchingMedia.get() || awaitingReady.get()) {
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
                if (switchingMedia.get() || awaitingReady.get()) {
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
                if (seeking.get() || switchingMedia.get() || awaitingReady.get()) {
                    return;
                }
                boolean reversed = usingReversedMedia;
                long original = playerTimeToOriginal(newTime, reversed);
                if (holdPlayhead.get()) {
                    if (!playheadMatchesPin(original)) {
                        return;
                    }
                    holdPlayhead.set(false);
                }
                Platform.runLater(() -> timeMs.set(original));
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
                    } else if (originalLengthMs > 0) {
                        lengthMs.set(originalLengthMs);
                    } else if (newLength > 0) {
                        lengthMs.set(newLength);
                    }
                });
            }

            @Override
            public void error(MediaPlayer player) {
                Platform.runLater(() -> recoverAfterDecoderError());
            }

            @Override
            public void mediaPlayerReady(MediaPlayer player) {
                // Play() already starts; ready is used only to apply rate/time if playing lags.
                if (awaitingReady.get()) {
                    Platform.runLater(() -> {
                        mediaPlayer.controls().setTime(
                                originalToPlayerTime(pendingReadyOriginalTime, pendingReadyReversed)
                        );
                        mediaPlayer.controls().setRate((float) Math.abs(rate.get() == 0 ? 1.0 : rate.get()));
                    });
                }
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
                long anchor = directionSwitchAnchorMs >= 0
                        ? directionSwitchAnchorMs
                        : capturePlayerPlaybackMs();
                if (pendingReverseActivation) {
                    boolean resume = userWantsPlayback || Math.abs(rate.get()) >= ZERO_RATE_EPS;
                    pendingReverseActivation = false;
                    hideReverseBadge();
                    loadDirection(true, anchor, resume);
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
                    rate.set(Math.abs(lastNonZeroRate));
                }
                statusMessage.set("No se pudo generar reversa: " + error.getMessage());
            }
            if (proxyService.activeReverseSource() == null) {
                hideReverseBadge();
            }
            bumpPlaylistInfo(true);
        }));

        proxyService.setStatusChangedListener(ignored -> Platform.runLater(this::bumpPlaylistInfo));

        proxyService.setCompatCompletedListener(result -> Platform.runLater(() -> {
            if (result.kind() != FfmpegProxyService.ProxyKind.COMPAT) {
                return;
            }
            Path source = result.source().toAbsolutePath().normalize();
            boolean isCurrent = originalFile != null
                    && originalFile.toPath().toAbsolutePath().normalize().equals(source);
            if (!isCurrent) {
                bumpPlaylistInfo(true);
                return;
            }
            forwardFile = result.proxyFile();
            if (!usingReversedMedia && !usingForwardProxy && !awaitingReady.get()) {
                boolean resume = userWantsPlayback || playing.get();
                loadDirection(false, capturePlayerPlaybackMs(), resume);
            }
            bumpPlaylistInfo(true);
        }));

        proxyService.setCompatFailedListener(error -> Platform.runLater(() -> {
            statusMessage.set("No se pudo generar el video hacia adelante: " + error.getMessage());
            bumpPlaylistInfo(true);
        }));
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
            showReverseBadge("Preparando encodes…");
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
        String forwardLine = status.forwardReady() ? "Adelante: listo" : "Adelante: generando…";
        return durationLine + "\n" + forwardLine + "\n" + reverseLine;
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
        awaitingReady.set(false);
        holdPlayhead.set(false);
        usingReversedMedia = false;
        usingForwardProxy = false;
        reversedFile = null;
        forwardFile = null;
        originalLengthMs = 0;
        directionSwitchAnchorMs = -1;

        originalFile = file;
        userWantsPlayback = true;
        mediaLoaded.set(false);
        timeMs.set(0);
        lengthMs.set(0);
        if (Math.abs(rate.get()) < ZERO_RATE_EPS || rate.get() < 0) {
            rate.set(1.0);
        }
        lastNonZeroRate = rate.get();

        try {
            if (proxyService.hasValidCache(file.toPath(), FfmpegProxyService.ProxyKind.COMPAT)) {
                forwardFile = proxyService.cacheFileFor(file.toPath(), FfmpegProxyService.ProxyKind.COMPAT);
            } else {
                proxyService.enqueueCompat(file.toPath());
            }
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

        String playPath = forwardFile != null ? forwardFile.toAbsolutePath().toString() : file.getAbsolutePath();
        usingForwardProxy = forwardFile != null;
        boolean ok = mediaPlayer.media().play(playPath);
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
        if (originalFile == null) {
            return;
        }
        if (Math.abs(rate.get()) < ZERO_RATE_EPS) {
            setRate(lastNonZeroRate);
            return;
        }
        userWantsPlayback = true;
        if (rate.get() < 0 && !usingReversedMedia) {
            switchToReversePlayback();
            return;
        }
        State state = mediaPlayer.status().state();
        if (state == State.STOPPED || state == State.ENDED || state == State.ERROR || state == State.NOTHING_SPECIAL) {
            reloadCurrentMedia(capturePlayerPlaybackMs(), usingReversedMedia, true);
            return;
        }
        mediaPlayer.controls().setRate((float) Math.abs(rate.get()));
        mediaPlayer.controls().play();
        playing.set(true);
        statusMessage.set(statusForRate());
        scheduleRecoverIfStuck();
    }

    public void pause() {
        userWantsPlayback = false;
        cancelRecoverTimer();
        awaitingReady.set(false);
        holdPlayhead.set(false);
        switchingMedia.set(false);
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
        awaitingReady.set(false);
        holdPlayhead.set(false);
        switchingMedia.set(false);
        cancelRecoverTimer();
        usingReversedMedia = false;
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
        long length = originalTimelineLength();
        long clamped = Math.max(0, length > 0 ? Math.min(originalMillis, length) : originalMillis);
        seeking.set(true);
        timeMs.set(clamped);
        mediaPlayer.controls().setTime(originalToPlayerTime(clamped, usingReversedMedia));
        seeking.set(false);
    }

    public void switchToReversePlayback() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        if (usingReversedMedia || pendingReverseActivation || awaitingReady.get()) {
            return;
        }
        setRate(-1.0);
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
                    rate.set(Math.abs(lastNonZeroRate));
                }
                if (!preparingReverse.get()) {
                    hideReverseBadge();
                }
            }
            return;
        }
        if (awaitingReady.get()) {
            return;
        }
        setRate(1.0);
    }

    public void setSlowReverse() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        setRate(-SLOW_MOTION_RATE);
    }

    public void setSlowForward() {
        if (originalFile == null) {
            statusMessage.set("No hay video cargado");
            return;
        }
        setRate(SLOW_MOTION_RATE);
    }

    public boolean isUsingReversedMedia() {
        return usingReversedMedia;
    }

    /**
     * Signed rate: positive = original, negative = reversed clip, ~0 = pause.
     */
    public void setRate(double newRate) {
        if (originalFile == null) {
            return;
        }
        double signed = sanitizeRate(newRate);
        if (Math.abs(signed) < ZERO_RATE_EPS) {
            rate.set(0);
            pause();
            return;
        }
        lastNonZeroRate = signed;
        rate.set(signed);
        userWantsPlayback = true;

        boolean wantReverse = signed < 0;
        if (wantReverse && !usingReversedMedia) {
            if (pendingReverseActivation || awaitingReady.get()) {
                return;
            }
            long savedMs = capturePlayerPlaybackMs();
            directionSwitchAnchorMs = savedMs;
            timeMs.set(savedMs);
            requestReversedPlayback(true, savedMs);
            return;
        }
        if (!wantReverse && usingReversedMedia) {
            if (awaitingReady.get()) {
                return;
            }
            long savedMs = capturePlayerPlaybackMs();
            directionSwitchAnchorMs = savedMs;
            timeMs.set(savedMs);
            pendingReverseActivation = false;
            loadDirection(false, savedMs, true);
            return;
        }
        if (awaitingReady.get()) {
            return;
        }

        mediaPlayer.controls().setRate((float) Math.abs(signed));
        mediaPlayer.controls().play();
        playing.set(true);
        statusMessage.set(statusForRate());
    }

    private long capturePlayerPlaybackMs() {
        long mediaTime = mediaPlayer.status().time();
        if (mediaTime < 0) {
            mediaTime = 0;
        }
        return clampToOriginalTimeline(playerTimeToOriginal(mediaTime, usingReversedMedia));
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
                loadDirection(true, atOriginalTime, resumePlayback);
                return;
            }
        } catch (IOException ignored) {
            // Fall through.
        }

        pendingReverseActivation = true;
        preparingReverse.set(true);
        userWantsPlayback = resumePlayback;
        freezeCurrentPlayback();
        showReverseBadge("Preparando reversa…");
        proxyService.enqueueReverse(originalFile.toPath(), true);
    }

    private void freezeCurrentPlayback() {
        playing.set(false);
        statusMessage.set("Cargando…");
        mediaPlayer.controls().pause();
    }

    private void loadDirection(boolean reversed, long originalTime, boolean resumePlayback) {
        if (reversed && reversedFile == null) {
            return;
        }
        if (!reversed && originalFile == null) {
            return;
        }
        cancelRecoverTimer();
        userWantsPlayback = resumePlayback;
        pendingResumeAfterReady = resumePlayback;
        pendingReadyReversed = reversed;
        pendingReadyOriginalTime = clampToOriginalTimeline(originalTime);
        directionSwitchAnchorMs = pendingReadyOriginalTime;
        timeMs.set(pendingReadyOriginalTime);
        usingReversedMedia = reversed;
        usingForwardProxy = !reversed && forwardFile != null;
        holdPlayhead.set(true);
        awaitingReady.set(true);
        switchingMedia.set(true);
        playing.set(false);
        statusMessage.set("Cargando…");

        String mrl = currentMrl(reversed);
        if (mrl == null) {
            awaitingReady.set(false);
            switchingMedia.set(false);
            return;
        }

        boolean ok = mediaPlayer.media().play(mrl);
        if (!ok) {
            awaitingReady.set(false);
            switchingMedia.set(false);
            statusMessage.set(reversed
                    ? "No se pudo abrir el video invertido"
                    : "No se pudo volver al video original");
            return;
        }
        mediaPlayer.audio().setVolume(volume.get());
        mediaPlayer.audio().setMute(muted.get());
        mediaPlayer.controls().setRate((float) Math.abs(rate.get() == 0 ? 1.0 : rate.get()));
        if (!resumePlayback || Math.abs(rate.get()) < ZERO_RATE_EPS) {
            awaitingReady.set(false);
            switchingMedia.set(false);
            mediaPlayer.controls().pause();
            playing.set(false);
            statusMessage.set("Pausa");
        }
    }

    private String currentMrl(boolean reversed) {
        if (reversed) {
            return reversedFile != null ? reversedFile.toAbsolutePath().toString() : null;
        }
        if (forwardFile != null) {
            return forwardFile.toAbsolutePath().toString();
        }
        return originalFile != null ? originalFile.getAbsolutePath() : null;
    }

    private void reloadCurrentMedia(long originalTime, boolean reversed, boolean resume) {
        loadDirection(reversed, originalTime, resume);
    }

    private void recoverAfterDecoderError() {
        if (!userWantsPlayback || originalFile == null || recoverAttempts >= 2) {
            awaitingReady.set(false);
            switchingMedia.set(false);
            playing.set(false);
            recoverAttempts = 0;
            userWantsPlayback = false;
            statusMessage.set("Error al reproducir el archivo");
            return;
        }
        recoverAttempts++;
        statusMessage.set("Reintentando reproducción…");
        reloadCurrentMedia(timeMs.get(), usingReversedMedia, true);
    }

    private void scheduleRecoverIfStuck() {
        cancelRecoverTimer();
        long originalTime = timeMs.get();
        boolean reversed = usingReversedMedia;
        recoverTimer = new PauseTransition(Duration.millis(450));
        recoverTimer.setOnFinished(e -> {
            if (!userWantsPlayback) {
                return;
            }
            State state = mediaPlayer.status().state();
            if (state == State.PLAYING || state == State.OPENING || state == State.BUFFERING) {
                return;
            }
            reloadCurrentMedia(originalTime, reversed, true);
        });
        recoverTimer.playFromStart();
    }

    private void cancelRecoverTimer() {
        if (recoverTimer != null) {
            recoverTimer.stop();
            recoverTimer = null;
        }
    }

    private void maybeFinishLoad() {
        if (!awaitingReady.get()) {
            return;
        }
        Platform.runLater(() -> {
            if (!awaitingReady.compareAndSet(true, false)) {
                return;
            }
            seeking.set(true);
            mediaPlayer.controls().setTime(
                    originalToPlayerTime(pendingReadyOriginalTime, pendingReadyReversed)
            );
            mediaPlayer.controls().setRate((float) Math.abs(rate.get() == 0 ? 1.0 : rate.get()));
            timeMs.set(pendingReadyOriginalTime);
            seeking.set(false);
            switchingMedia.set(false);
            if (pendingResumeAfterReady && Math.abs(rate.get()) >= ZERO_RATE_EPS) {
                userWantsPlayback = true;
                mediaPlayer.controls().play();
                playing.set(true);
                statusMessage.set(statusForRate());
            }
        });
    }

    private boolean playheadMatchesPin(long originalTime) {
        return Math.abs(originalTime - pendingReadyOriginalTime) <= 2000;
    }

    private String statusForRate() {
        if (Math.abs(rate.get()) < ZERO_RATE_EPS) {
            return "Pausa";
        }
        if (rate.get() < 0 || usingReversedMedia) {
            return String.format("Reversa %.2fx", rate.get());
        }
        return String.format("Velocidad: %.2fx", Math.abs(rate.get()));
    }

    private long playerTimeToOriginal(long mediaTime, boolean reversed) {
        if (!reversed) {
            return mediaTime;
        }
        long orig = originalTimelineLength();
        if (orig <= 0) {
            return mediaTime;
        }
        return Math.max(0, orig - Math.max(0, mediaTime));
    }

    private long originalToPlayerTime(long originalTime, boolean reversed) {
        if (!reversed) {
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
        if (Math.abs(clamped) < ZERO_RATE_EPS) {
            return 0;
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
                forwardFile = null;
                usingForwardProxy = false;
            }
        } catch (Exception ignored) {
            reversedFile = null;
            forwardFile = null;
            usingForwardProxy = false;
        }
        bumpPlaylistInfo(true);
    }

    @Override
    public void close() {
        userWantsPlayback = false;
        awaitingReady.set(false);
        cancelRecoverTimer();
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
