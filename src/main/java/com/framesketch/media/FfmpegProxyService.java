package com.framesketch.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FFmpeg helpers: compatible MP4 for JavaFX, and a queued reversed-clip cache.
 * Reverse jobs are queued (not cancelled when switching videos) so playlist
 * items can be prepared ahead of time.
 */
public final class FfmpegProxyService implements AutoCloseable {

    public enum ProxyKind {
        COMPAT("-compat.mp4"),
        REVERSE("-rev.mp4");

        private final String suffix;

        ProxyKind(String suffix) {
            this.suffix = suffix;
        }

        String suffix() {
            return suffix;
        }
    }

    public record ProxyJobResult(Path proxyFile, ProxyKind kind, Path source) {
    }

    public record ReverseProgress(Path source, String timeLabel) {
    }

    public enum ReverseStatus {
        READY,
        RUNNING,
        QUEUED,
        PENDING,
        FAILED
    }

    public record FileStatus(
            OptionalLong durationMs,
            ReverseStatus reverseStatus,
            Optional<String> reverseProgressTime,
            boolean forwardReady
    ) {
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2}\\.\\d+)");

    private final ExecutorService reverseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ffmpeg-reverse-queue");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService compatExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ffmpeg-compat");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ffprobe-duration");
        t.setDaemon(true);
        return t;
    });

    private final Path cacheDir;
    private final Object reverseLock = new Object();
    private final Deque<ReverseJob> reverseQueue = new ArrayDeque<>();
    private final AtomicBoolean reverseWorkerRunning = new AtomicBoolean(false);
    private final AtomicBoolean reverseCancelled = new AtomicBoolean(false);
    private volatile Process activeReverseProcess;
    private volatile Path activeReverseSource;

    private final Map<Path, Long> durationCacheMs = new ConcurrentHashMap<>();
    private final Map<Path, String> reverseProgressBySource = new ConcurrentHashMap<>();
    private final Map<Path, ReverseStatus> reverseStatusOverride = new ConcurrentHashMap<>();

    private Consumer<ReverseProgress> reverseProgressListener = p -> {
    };
    private Consumer<ProxyJobResult> reverseCompletedListener = r -> {
    };
    private Consumer<Exception> reverseFailedListener = e -> {
    };
    private Consumer<ProxyJobResult> compatCompletedListener = r -> {
    };
    private Consumer<Exception> compatFailedListener = e -> {
    };
    private Consumer<Path> statusChangedListener = p -> {
    };
    private final java.util.Set<Path> compatInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FfmpegProxyService() throws IOException {
        cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "framesketch-proxy-cache");
        Files.createDirectories(cacheDir);
    }

    public void setReverseProgressListener(Consumer<ReverseProgress> listener) {
        this.reverseProgressListener = listener != null ? listener : p -> {
        };
    }

    public void setReverseCompletedListener(Consumer<ProxyJobResult> listener) {
        this.reverseCompletedListener = listener != null ? listener : r -> {
        };
    }

    public void setReverseFailedListener(Consumer<Exception> listener) {
        this.reverseFailedListener = listener != null ? listener : e -> {
        };
    }

    public void setCompatCompletedListener(Consumer<ProxyJobResult> listener) {
        this.compatCompletedListener = listener != null ? listener : r -> {
        };
    }

    public void setCompatFailedListener(Consumer<Exception> listener) {
        this.compatFailedListener = listener != null ? listener : e -> {
        };
    }

    public void setStatusChangedListener(Consumer<Path> listener) {
        this.statusChangedListener = listener != null ? listener : p -> {
        };
    }

    public static Optional<String> findFfmpeg() {
        List<String> candidates = new ArrayList<>();
        String override = System.getenv("FRAMESKETCH_FFMPEG");
        if (override != null && !override.isBlank()) {
            candidates.add(override.trim());
        }
        candidates.add("ffmpeg");
        candidates.add("/opt/homebrew/bin/ffmpeg");
        candidates.add("/usr/local/bin/ffmpeg");
        candidates.add("/usr/bin/ffmpeg");

        for (String candidate : candidates) {
            if (isUsable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isUsable(String binary) {
        try {
            Path path = Path.of(binary);
            if (path.isAbsolute() && (!Files.isRegularFile(path) || !Files.isExecutable(path))) {
                return false;
            }
            Process process = new ProcessBuilder(binary, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    public static Optional<String> findFfprobe() {
        List<String> candidates = new ArrayList<>();
        String override = System.getenv("FRAMESKETCH_FFPROBE");
        if (override != null && !override.isBlank()) {
            candidates.add(override.trim());
        }
        Optional<String> ffmpeg = findFfmpeg();
        if (ffmpeg.isPresent()) {
            Path ffmpegPath = Path.of(ffmpeg.get());
            if (ffmpegPath.getParent() != null) {
                candidates.add(ffmpegPath.getParent().resolve("ffprobe").toString());
            }
        }
        candidates.add("ffprobe");
        candidates.add("/opt/homebrew/bin/ffprobe");
        candidates.add("/usr/local/bin/ffprobe");
        candidates.add("/usr/bin/ffprobe");

        for (String candidate : candidates) {
            if (isUsable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public Path cacheFileFor(Path source, ProxyKind kind) throws IOException {
        String key = hashKey(source);
        return cacheDir.resolve(key + kind.suffix());
    }

    public boolean hasValidCache(Path source, ProxyKind kind) throws IOException {
        Path cache = cacheFileFor(source, kind);
        if (!Files.isRegularFile(cache) || Files.size(cache) <= 0) {
            return false;
        }
        return Files.getLastModifiedTime(cache).toMillis() >= Files.getLastModifiedTime(source).toMillis();
    }

    public Path activeReverseSource() {
        return activeReverseSource;
    }

    public FileStatus statusFor(Path source) {
        Path absolute = source.toAbsolutePath().normalize();
        OptionalLong duration = OptionalLong.empty();
        Long cached = durationCacheMs.get(absolute);
        if (cached != null && cached > 0) {
            duration = OptionalLong.of(cached);
        }

        ReverseStatus reverseStatus;
        Optional<String> progress = Optional.empty();
        try {
            if (hasValidCache(absolute, ProxyKind.REVERSE)) {
                reverseStatus = ReverseStatus.READY;
            } else if (absolute.equals(activeReverseSource)) {
                reverseStatus = ReverseStatus.RUNNING;
                progress = Optional.ofNullable(reverseProgressBySource.get(absolute));
            } else if (isQueued(absolute)) {
                reverseStatus = ReverseStatus.QUEUED;
            } else if (reverseStatusOverride.get(absolute) == ReverseStatus.FAILED) {
                reverseStatus = ReverseStatus.FAILED;
            } else {
                reverseStatus = ReverseStatus.PENDING;
            }
        } catch (IOException ex) {
            reverseStatus = ReverseStatus.PENDING;
        }
        boolean forwardReady = false;
        try {
            forwardReady = hasValidCache(absolute, ProxyKind.COMPAT);
        } catch (IOException ignored) {
            forwardReady = false;
        }
        return new FileStatus(duration, reverseStatus, progress, forwardReady);
    }

    public void rememberDurationMs(Path source, long durationMs) {
        if (source == null || durationMs <= 0) {
            return;
        }
        Path absolute = source.toAbsolutePath().normalize();
        durationCacheMs.put(absolute, durationMs);
        statusChangedListener.accept(absolute);
    }

    public void probeDurationAsync(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return;
        }
        Path absolute = source.toAbsolutePath().normalize();
        if (durationCacheMs.containsKey(absolute)) {
            return;
        }
        probeExecutor.submit(() -> {
            try {
                OptionalLong ms = probeDurationMs(absolute);
                if (ms.isPresent()) {
                    durationCacheMs.put(absolute, ms.getAsLong());
                    statusChangedListener.accept(absolute);
                }
            } catch (Exception ignored) {
                // Duration stays unknown.
            }
        });
    }

    public void probeDurationsAsync(Iterable<Path> sources) {
        for (Path source : sources) {
            probeDurationAsync(source);
        }
    }

    private OptionalLong probeDurationMs(Path source) throws IOException, InterruptedException {
        String ffprobe = findFfprobe().orElse(null);
        if (ffprobe == null) {
            return OptionalLong.empty();
        }
        Process process = new ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.readLine();
        }
        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return OptionalLong.empty();
        }
        if (process.exitValue() != 0 || output == null || output.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            double seconds = Double.parseDouble(output.trim());
            if (seconds > 0 && Double.isFinite(seconds)) {
                return OptionalLong.of(Math.round(seconds * 1000.0));
            }
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    private boolean isQueued(Path absolute) {
        synchronized (reverseLock) {
            for (ReverseJob job : reverseQueue) {
                if (job.source.equals(absolute)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Enqueues reverse generation if missing. Does not cancel other queued jobs.
     * When {@code prioritize} is true, the job moves to the front of the queue.
     */
    public void enqueueReverse(Path source, boolean prioritize) {
        Objects.requireNonNull(source, "source");
        if (findFfmpeg().isEmpty()) {
            return;
        }
        Path absolute = source.toAbsolutePath().normalize();
        probeDurationAsync(absolute);

        try {
            if (hasValidCache(absolute, ProxyKind.REVERSE)) {
                reverseStatusOverride.put(absolute, ReverseStatus.READY);
                reverseProgressBySource.remove(absolute);
                reverseCompletedListener.accept(new ProxyJobResult(
                        cacheFileFor(absolute, ProxyKind.REVERSE), ProxyKind.REVERSE, absolute));
                statusChangedListener.accept(absolute);
                return;
            }
        } catch (IOException ex) {
            reverseStatusOverride.put(absolute, ReverseStatus.FAILED);
            reverseFailedListener.accept(ex);
            statusChangedListener.accept(absolute);
            return;
        }

        synchronized (reverseLock) {
            // Already running this source.
            if (absolute.equals(activeReverseSource)) {
                return;
            }
            // Already queued.
            for (ReverseJob job : reverseQueue) {
                if (job.source.equals(absolute)) {
                    if (prioritize) {
                        reverseQueue.remove(job);
                        reverseQueue.addFirst(job);
                    }
                    statusChangedListener.accept(absolute);
                    ensureReverseWorker();
                    return;
                }
            }
            ReverseJob job = new ReverseJob(absolute);
            if (prioritize) {
                reverseQueue.addFirst(job);
            } else {
                reverseQueue.addLast(job);
            }
            reverseStatusOverride.put(absolute, ReverseStatus.QUEUED);
            statusChangedListener.accept(absolute);
            ensureReverseWorker();
        }
    }

    public void enqueueReverseAll(Iterable<Path> sources) {
        for (Path source : sources) {
            if (source != null) {
                enqueueReverse(source, false);
                enqueueCompat(source);
            }
        }
    }

    /**
     * Encodes a forward-playback H.264 proxy if missing.
     */
    public void enqueueCompat(Path source) {
        Objects.requireNonNull(source, "source");
        if (findFfmpeg().isEmpty()) {
            return;
        }
        Path absolute = source.toAbsolutePath().normalize();
        probeDurationAsync(absolute);
        try {
            if (hasValidCache(absolute, ProxyKind.COMPAT)) {
                compatCompletedListener.accept(new ProxyJobResult(
                        cacheFileFor(absolute, ProxyKind.COMPAT), ProxyKind.COMPAT, absolute));
                statusChangedListener.accept(absolute);
                return;
            }
        } catch (IOException ex) {
            compatFailedListener.accept(ex);
            statusChangedListener.accept(absolute);
            return;
        }
        if (!compatInFlight.add(absolute)) {
            return;
        }
        prepareCompatAsync(absolute, result -> {
            compatInFlight.remove(absolute);
            compatCompletedListener.accept(result);
            statusChangedListener.accept(absolute);
        }, error -> {
            compatInFlight.remove(absolute);
            compatFailedListener.accept(error);
            statusChangedListener.accept(absolute);
        });
    }

    /**
     * Runs a one-off compat conversion without clearing the reverse queue.
     */
    public void prepareCompatAsync(
            Path source,
            Consumer<ProxyJobResult> onSuccess,
            Consumer<Exception> onError
    ) {
        compatExecutor.submit(() -> {
            try {
                Path result = prepareBlocking(source, ProxyKind.COMPAT, null);
                onSuccess.accept(new ProxyJobResult(result, ProxyKind.COMPAT, source));
            } catch (Exception ex) {
                onError.accept(ex);
            }
        });
    }

    private void ensureReverseWorker() {
        if (!reverseWorkerRunning.compareAndSet(false, true)) {
            return;
        }
        reverseExecutor.submit(this::drainReverseQueue);
    }

    private void drainReverseQueue() {
        try {
            while (true) {
                ReverseJob job;
                synchronized (reverseLock) {
                    job = reverseQueue.pollFirst();
                    if (job == null) {
                        activeReverseSource = null;
                        reverseWorkerRunning.set(false);
                        // Recheck race: job added after poll null but before flag clear.
                        if (!reverseQueue.isEmpty() && reverseWorkerRunning.compareAndSet(false, true)) {
                            continue;
                        }
                        return;
                    }
                    activeReverseSource = job.source;
                    reverseCancelled.set(false);
                    reverseStatusOverride.put(job.source, ReverseStatus.RUNNING);
                    reverseProgressBySource.put(job.source, "time=00:00:00.00");
                }
                statusChangedListener.accept(job.source);

                try {
                    Path out = prepareBlocking(job.source, ProxyKind.REVERSE, time -> {
                        reverseProgressBySource.put(job.source, time);
                        reverseProgressListener.accept(new ReverseProgress(job.source, time));
                        statusChangedListener.accept(job.source);
                    });
                    reverseStatusOverride.put(job.source, ReverseStatus.READY);
                    reverseProgressBySource.remove(job.source);
                    statusChangedListener.accept(job.source);
                    reverseCompletedListener.accept(new ProxyJobResult(out, ProxyKind.REVERSE, job.source));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    synchronized (reverseLock) {
                        reverseQueue.addFirst(job);
                        reverseStatusOverride.put(job.source, ReverseStatus.QUEUED);
                        activeReverseSource = null;
                        reverseWorkerRunning.set(false);
                    }
                    statusChangedListener.accept(job.source);
                    return;
                } catch (Exception ex) {
                    reverseStatusOverride.put(job.source, ReverseStatus.FAILED);
                    reverseProgressBySource.remove(job.source);
                    statusChangedListener.accept(job.source);
                    reverseFailedListener.accept(ex);
                } finally {
                    activeReverseProcess = null;
                    synchronized (reverseLock) {
                        if (job.source.equals(activeReverseSource)) {
                            activeReverseSource = null;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            reverseWorkerRunning.set(false);
            activeReverseSource = null;
        }
    }

    private Path prepareBlocking(Path source, ProxyKind kind, Consumer<String> progressCallback)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Archivo de video no encontrado: " + source);
        }

        String ffmpeg = findFfmpeg().orElseThrow(() -> new IOException(
                "No se encontró FFmpeg. Instálalo (p. ej. brew install ffmpeg) o define FRAMESKETCH_FFMPEG."
        ));

        Path output = cacheFileFor(source, kind);
        if (hasValidCache(source, kind)) {
            return output;
        }

        Path temp = cacheDir.resolve(output.getFileName().toString() + ".partial.mp4");
        Files.deleteIfExists(temp);
        Files.deleteIfExists(output);

        try {
            runFfmpeg(ffmpeg, source, temp, kind, true, progressCallback);
        } catch (IOException first) {
            Files.deleteIfExists(temp);
            runFfmpeg(ffmpeg, source, temp, kind, false, progressCallback);
        }

        if (kind == ProxyKind.REVERSE && reverseCancelled.get()) {
            Files.deleteIfExists(temp);
            throw new InterruptedException("Conversión cancelada");
        }

        Files.move(temp, output);
        return output;
    }

    private void runFfmpeg(
            String ffmpeg,
            Path source,
            Path temp,
            ProxyKind kind,
            boolean withAudio,
            Consumer<String> progressCallback
    ) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(source.toAbsolutePath().toString());

        if (kind == ProxyKind.REVERSE) {
            command.add("-vf");
            command.add("reverse");
            if (withAudio) {
                command.add("-af");
                command.add("areverse");
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
            } else {
                command.add("-an");
            }
        } else if (withAudio) {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
        } else {
            command.add("-an");
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-g");
        command.add("30");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("+faststart");
        command.add(temp.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (kind == ProxyKind.REVERSE) {
            activeReverseProcess = process;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()
                        || (kind == ProxyKind.REVERSE && reverseCancelled.get())) {
                    process.destroyForcibly();
                    throw new InterruptedException("Conversión cancelada");
                }
                String time = extractTime(line);
                if (time != null && progressCallback != null) {
                    progressCallback.accept("time=" + time);
                }
            }
        }

        int code = process.waitFor();
        if (kind == ProxyKind.REVERSE && reverseCancelled.get()) {
            Files.deleteIfExists(temp);
            throw new InterruptedException("Conversión cancelada");
        }
        if (code != 0) {
            Files.deleteIfExists(temp);
            throw new IOException("FFmpeg falló (código " + code + ")");
        }
    }

    private static String extractTime(String line) {
        Matcher matcher = TIME_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String hashKey(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = source.toAbsolutePath() + "|" + Files.size(source) + "|" + Files.getLastModifiedTime(source);
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(source.toAbsolutePath().toString().toLowerCase(Locale.ROOT).hashCode());
        }
    }

    @Override
    public void close() {
        reverseCancelled.set(true);
        Process proc = activeReverseProcess;
        if (proc != null) {
            proc.destroyForcibly();
        }
        synchronized (reverseLock) {
            reverseQueue.clear();
        }
        clearAllCache();
        reverseExecutor.shutdownNow();
        compatExecutor.shutdownNow();
        probeExecutor.shutdownNow();
    }

    /**
     * Deletes cached proxy files for one source and drops it from the reverse queue.
     */
    public void clearCacheFor(Path source) {
        if (source == null) {
            return;
        }
        Path absolute = source.toAbsolutePath().normalize();
        synchronized (reverseLock) {
            reverseQueue.removeIf(job -> job.source.equals(absolute));
            if (absolute.equals(activeReverseSource)) {
                reverseCancelled.set(true);
                Process proc = activeReverseProcess;
                if (proc != null) {
                    proc.destroyForcibly();
                }
            }
        }
        for (ProxyKind kind : ProxyKind.values()) {
            try {
                Path cache = cacheFileFor(absolute, kind);
                Files.deleteIfExists(cache);
                Files.deleteIfExists(cacheDir.resolve(cache.getFileName().toString() + ".partial.mp4"));
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
        durationCacheMs.remove(absolute);
        reverseProgressBySource.remove(absolute);
        reverseStatusOverride.remove(absolute);
        compatInFlight.remove(absolute);
        statusChangedListener.accept(absolute);
    }

    /** Deletes the entire FrameSketch proxy cache directory contents. */
    public void clearAllCache() {
        synchronized (reverseLock) {
            reverseQueue.clear();
        }
        reverseCancelled.set(true);
        Process proc = activeReverseProcess;
        if (proc != null) {
            proc.destroyForcibly();
        }
        try (var stream = Files.list(cacheDir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Cache dir may already be gone.
        }
        durationCacheMs.clear();
        reverseProgressBySource.clear();
        reverseStatusOverride.clear();
        compatInFlight.clear();
    }

    private record ReverseJob(Path source) {
    }
}
