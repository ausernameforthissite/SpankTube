package dev.spanktube.archive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.spanktube.egress.EgressProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class YtDlpDownloader {

    private final ArchiveProperties archiveProperties;
    private final EgressProperties egressProperties;
    private final String ytDlpExecutable;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;
    private final ObjectMapper objectMapper;

    YtDlpDownloader(ArchiveProperties archiveProperties, EgressProperties egressProperties,
            @Value("${spanktube.archive.yt-dlp-executable:yt-dlp}") String ytDlpExecutable,
            @Value("${spanktube.archive.ffmpeg-executable:ffmpeg}") String ffmpegExecutable,
            @Value("${spanktube.archive.ffprobe-executable:ffprobe}") String ffprobeExecutable,
            ObjectMapper objectMapper) {
        this.archiveProperties = archiveProperties;
        this.egressProperties = egressProperties;
        this.ytDlpExecutable = ytDlpExecutable;
        this.ffmpegExecutable = ffmpegExecutable;
        this.ffprobeExecutable = ffprobeExecutable;
        this.objectMapper = objectMapper;
    }

    long download(ArchiveModels.ExistingVideo metadata) {
        Path directory = Path.of(archiveProperties.root()).resolve(metadata.channelId()).resolve(metadata.videoId());
        return download(metadata, ArchiveModels.DownloadOptions.archiveDefaults(), directory, 0, progress -> { }).sizeBytes();
    }

    ArchiveModels.DownloadedFile inspect(Path output, ArchiveModels.DownloadOptions options) {
        try {
            return metadata(output, options.audioOnly() ? "mp3" : "mkv", options,
                    output.getParent().resolve("info.json"));
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to inspect media file: " + error.getMessage(), error);
        }
    }

    ArchiveModels.DownloadedFile download(ArchiveModels.ExistingVideo metadata,
            ArchiveModels.DownloadOptions options, Path directory, long estimatedBytes,
            Consumer<DownloadProgress.Snapshot> progress) {
        Path root = Path.of(archiveProperties.root()).toAbsolutePath().normalize();
        directory = directory.toAbsolutePath().normalize();
        if (!directory.startsWith(root)) {
            throw new ArchiveDownloadException("Download directory escapes archive root");
        }
        try {
            Files.createDirectories(directory);
            Path video = directory.resolve("video.mkv");
            Path audio = directory.resolve("audio.mp3");
            Files.deleteIfExists(video);
            Files.deleteIfExists(audio);
            List<String> command = new ArrayList<>(List.of(
                    ytDlpExecutable,
                    "--format", format(options),
                    "--no-mtime",
                    "--no-playlist",
                    "--no-warnings",
                    "--socket-timeout", "30",
                    "--retries", "3",
                    "--sleep-requests", "1",
                    "--sleep-subtitles", "2",
                    "--write-info-json",
                    "--write-thumbnail",
                    "--output", directory.resolve(options.audioOnly() ? "audio.%(ext)s" : "video.%(ext)s").toString()));
            if (!options.audioOnly()) {
                command.addAll(List.of("--merge-output-format", "mkv", "--embed-chapters"));
            }
            if (options.subtitles()) {
                command.addAll(List.of("--write-subs", "--sub-langs", "all"));
                if (!options.audioOnly()) {
                    command.add("--embed-subs");
                }
            }
            if (options.sponsorBlock()) {
                command.addAll(List.of("--sponsorblock-mark", "all"));
            }
            if (egressProperties.mode() == EgressProperties.Mode.SOCKS5) {
                command.add("--proxy");
                command.add(proxyUrl());
            }
            command.add("https://www.youtube.com/watch?v=" + metadata.videoId());
            run(command, directory, archiveProperties.downloadTimeout(), estimatedBytes, progress);

            Path output;
            String extension;
            if (options.audioOnly()) {
                Path source = audioSource(directory);
                output = audio;
                progress.accept(new DownloadProgress.Snapshot(ArchiveModels.Stage.MUXING, 0, estimatedBytes, 0));
                run(List.of(ffmpegExecutable, "-y", "-i", source.toString(), "-map", "0:a:0", "-vn",
                        "-c:a", "libmp3lame", "-q:a", "0", "-map_metadata", "0", "-map_chapters", "0",
                        output.toString()), directory, Duration.ofMinutes(15), estimatedBytes, progress);
                extension = "mp3";
            } else {
                output = video;
                if (!Files.isRegularFile(output)) {
                    throw new ArchiveDownloadException("yt-dlp did not produce an MKV file");
                }
                Path remux = directory.resolve("video.remux.mkv");
                progress.accept(new DownloadProgress.Snapshot(ArchiveModels.Stage.MUXING, 0, estimatedBytes, 0));
                run(List.of(ffmpegExecutable, "-y", "-i", output.toString(), "-map", "0",
                        "-c", "copy", "-c:s", "srt", "-map_chapters", "0", "-map_metadata", "0",
                        remux.toString()), directory, Duration.ofMinutes(15), estimatedBytes, progress);
                Files.move(remux, output, StandardCopyOption.REPLACE_EXISTING);
                extension = "mkv";
            }
            Path infoJson = directory.resolve(options.audioOnly() ? "audio.info.json" : "video.info.json");
            if (Files.exists(infoJson)) {
                Files.move(infoJson, directory.resolve("info.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            return metadata(output, extension, options, directory.resolve("info.json"));
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to write archive on NFS: " + error.getMessage(), error);
        }
    }

    void discard(ArchiveModels.ExistingVideo metadata) {
        discard(Path.of(archiveProperties.root()).resolve(metadata.channelId()).resolve(metadata.videoId()));
    }

    void discard(Path directory) {
        Path root = Path.of(archiveProperties.root()).toAbsolutePath().normalize();
        directory = directory.toAbsolutePath().normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    org.slf4j.LoggerFactory.getLogger(YtDlpDownloader.class)
                            .warn("Unable to remove incomplete archive file {}", path, error);
                }
            });
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger(YtDlpDownloader.class)
                    .warn("Unable to remove incomplete archive directory {}", directory, error);
        }
    }

    private ArchiveModels.DownloadedFile metadata(Path output, String extension,
            ArchiveModels.DownloadOptions options, Path infoJson) throws IOException {
        String probe = run(List.of(ffprobeExecutable, "-v", "error", "-show_entries",
                "stream=codec_type,codec_name,width,height", "-of", "json", output.toString()),
                output.getParent(), Duration.ofMinutes(2), 0, progress -> { });
        Integer width = null;
        Integer height = null;
        String videoCodec = null;
        String audioCodec = null;
        for (JsonNode stream : objectMapper.readTree(probe).path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                width = stream.path("width").isNumber() ? stream.path("width").asInt() : null;
                height = stream.path("height").isNumber() ? stream.path("height").asInt() : null;
                videoCodec = stream.path("codec_name").asText();
            } else if ("audio".equals(stream.path("codec_type").asText())) {
                audioCodec = stream.path("codec_name").asText();
            }
        }
        String videoFormat = options.videoFormatId();
        String audioFormat = options.audioFormatId();
        if (Files.isRegularFile(infoJson)) {
            String selected = objectMapper.readTree(Files.readString(infoJson)).path("format_id").asText("");
            String[] tracks = selected.split("\\+");
            if (options.audioOnly() && tracks.length > 0) {
                audioFormat = tracks[0];
            } else {
                if (tracks.length > 0 && !tracks[0].isBlank()) {
                    videoFormat = tracks[0];
                }
                if (tracks.length > 1 && !tracks[1].isBlank()) {
                    audioFormat = tracks[1];
                }
            }
        }
        return new ArchiveModels.DownloadedFile(Files.size(output), width, height, videoCodec, audioCodec,
                videoFormat, audioFormat, extension);
    }

    private static String format(ArchiveModels.DownloadOptions options) {
        if (options.videoFormatId() == null && options.audioFormatId() == null) {
            return "bv*+ba";
        }
        if (options.videoOnly()) {
            return options.videoFormatId();
        }
        if (options.audioOnly()) {
            return options.audioFormatId();
        }
        return options.videoFormatId() + "+" + options.audioFormatId();
    }

    private static Path audioSource(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("audio.") && !name.endsWith(".vtt") && !name.endsWith(".json")
                                && !name.endsWith(".jpg") && !name.endsWith(".webp")
                                && !name.endsWith(".png") && !name.endsWith(".mp3");
                    })
                    .findFirst()
                    .orElseThrow(() -> new ArchiveDownloadException("yt-dlp did not produce an audio file"));
        }
    }

    private String run(List<String> command, Path directory, Duration timeout, long estimatedBytes,
            Consumer<DownloadProgress.Snapshot> progress) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream(), executableName(command.getFirst()),
                    estimatedBytes, progress);
            CompletableFuture<String> stderr = readAsync(process.getErrorStream(), executableName(command.getFirst()),
                    estimatedBytes, progress);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ArchiveDownloadException("Process timed out: " + executableName(command.getFirst()));
            }
            String errorOutput = stderr.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ArchiveDownloadException(executableName(command.getFirst()) + " failed: " + sanitize(errorOutput));
            }
            return stdout.get(2, TimeUnit.SECONDS);
        } catch (IOException | ExecutionException | TimeoutException error) {
            throw new ArchiveDownloadException("Unable to execute " + executableName(command.getFirst()) + ": "
                    + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ArchiveDownloadException("Interrupted while waiting for " + executableName(command.getFirst()), error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String proxyUrl() {
        StringBuilder result = new StringBuilder(egressProperties.remoteDns() ? "socks5h://" : "socks5://");
        if (egressProperties.username() != null && !egressProperties.username().isBlank()) {
            result.append(encode(egressProperties.username())).append(':')
                    .append(encode(egressProperties.password())).append('@');
        }
        String host = egressProperties.host();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return result.append(host).append(':').append(egressProperties.port()).toString();
    }

    private String sanitize(String value) {
        String sanitized = value == null || value.isBlank() ? "No error output" : value.trim();
        if (egressProperties.password() != null && !egressProperties.password().isBlank()) {
            sanitized = sanitized.replace(egressProperties.password(), "***");
            sanitized = sanitized.replace(encode(egressProperties.password()), "***");
        }
        return sanitized.length() > 4000 ? sanitized.substring(0, 4000) : sanitized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String executableName(String command) {
        return Path.of(command).getFileName().toString();
    }

    private CompletableFuture<String> readAsync(InputStream input, String executable, long estimatedBytes,
            Consumer<DownloadProgress.Snapshot> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                DownloadProgress.Tracker tracker = new DownloadProgress.Tracker();
                return new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))
                        .lines()
                        .peek(line -> {
                            tracker.accept(line, estimatedBytes).ifPresent(progress);
                            org.slf4j.LoggerFactory.getLogger(YtDlpDownloader.class)
                                    .info("{}: {}", executable, sanitize(line));
                        })
                        .collect(java.util.stream.Collectors.joining("\n"));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
    }
}
