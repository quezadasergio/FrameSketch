package com.framesketch.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Builds and caches fully reversed video files with FFmpeg for smooth reverse playback.
 */
public final class FfmpegReverseService implements AutoCloseable {

    public record ReverseJobResult(Path reversedFile) {
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ffmpeg-reverse");
        t.setDaemon(true);
        return t;
    });

    private final Path cacheDir;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Future<?> currentJob;

    public FfmpegReverseService() throws IOException {
        cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "framesketch-reverse-cache");
        Files.createDirectories(cacheDir);
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

    public Path cacheFileFor(Path source) throws IOException {
        String key = hashKey(source);
        return cacheDir.resolve(key + "-rev.mp4");
    }

    public boolean hasValidCache(Path source) throws IOException {
        Path cache = cacheFileFor(source);
        if (!Files.isRegularFile(cache) || Files.size(cache) <= 0) {
            return false;
        }
        return Files.getLastModifiedTime(cache).toMillis() >= Files.getLastModifiedTime(source).toMillis();
    }

    public synchronized void cancelCurrent() {
        cancelled.set(true);
        if (currentJob != null) {
            currentJob.cancel(true);
        }
    }

    public synchronized Future<?> prepareAsync(
            Path source,
            Consumer<String> statusCallback,
            Consumer<ReverseJobResult> onSuccess,
            Consumer<Exception> onError
    ) {
        cancelCurrent();
        cancelled.set(false);
        currentJob = executor.submit(() -> {
            try {
                Path result = prepare(source, statusCallback);
                if (!cancelled.get()) {
                    onSuccess.accept(new ReverseJobResult(result));
                }
            } catch (Exception ex) {
                if (!cancelled.get()) {
                    onError.accept(ex);
                }
            }
        });
        return currentJob;
    }

    public Path prepare(Path source, Consumer<String> statusCallback) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source");
        if (!Files.isRegularFile(source)) {
            throw new IOException("Archivo de video no encontrado: " + source);
        }

        String ffmpeg = findFfmpeg().orElseThrow(() -> new IOException(
                "No se encontró FFmpeg. Instálalo (p. ej. brew install ffmpeg) o define FRAMESKETCH_FFMPEG."
        ));

        Path output = cacheFileFor(source);
        if (hasValidCache(source)) {
            status("Usando caché de video invertido", statusCallback);
            return output;
        }

        Path temp = cacheDir.resolve(output.getFileName().toString() + ".partial.mp4");
        Files.deleteIfExists(temp);
        Files.deleteIfExists(output);

        status("Generando video en reversa con FFmpeg (puede tardar)...", statusCallback);

        try {
            runFfmpegReverse(ffmpeg, source, temp, true, statusCallback);
        } catch (IOException first) {
            Files.deleteIfExists(temp);
            status("Reintentando reversa sin audio...", statusCallback);
            runFfmpegReverse(ffmpeg, source, temp, false, statusCallback);
        }

        if (cancelled.get()) {
            Files.deleteIfExists(temp);
            throw new InterruptedException("Generación de reversa cancelada");
        }

        Files.move(temp, output);
        status("Video en reversa listo", statusCallback);
        return output;
    }

    private void runFfmpegReverse(
            String ffmpeg,
            Path source,
            Path temp,
            boolean withAudio,
            Consumer<String> statusCallback
    ) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-y");
        command.add("-i");
        command.add(source.toAbsolutePath().toString());
        command.add("-vf");
        command.add("reverse");
        if (withAudio) {
            command.add("-af");
            command.add("areverse");
            command.add("-c:a");
            command.add("aac");
        } else {
            command.add("-an");
        }
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-crf");
        command.add("23");
        command.add("-movflags");
        command.add("+faststart");
        command.add(temp.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new InterruptedException("Generación de reversa cancelada");
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("frame=") || trimmed.contains("time=")) {
                    status("Generando reversa... " + summarizeProgress(trimmed), statusCallback);
                }
            }
        }

        int code = process.waitFor();
        if (cancelled.get()) {
            Files.deleteIfExists(temp);
            throw new InterruptedException("Generación de reversa cancelada");
        }
        if (code != 0) {
            Files.deleteIfExists(temp);
            throw new IOException("FFmpeg falló al invertir el video (código " + code + ")");
        }
    }

    private static String summarizeProgress(String line) {
        int timeIdx = line.indexOf("time=");
        if (timeIdx >= 0) {
            int end = line.indexOf(' ', timeIdx + 5);
            String time = end > timeIdx ? line.substring(timeIdx, end) : line.substring(timeIdx);
            return time;
        }
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }

    private static void status(String message, Consumer<String> callback) {
        if (callback != null) {
            callback.accept(message);
        }
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
        cancelCurrent();
        executor.shutdownNow();
    }
}
