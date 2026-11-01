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

import dev.spanktube.egress.EgressProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class YtDlpDownloader {

    private final ArchiveProperties archiveProperties;
    private final EgressProperties egressProperties;
    private final String ytDlpExecutable;
    private final String ffmpegExecutable;

    YtDlpDownloader(ArchiveProperties archiveProperties, EgressProperties egressProperties,
            @Value("${spanktube.archive.yt-dlp-executable:yt-dlp}") String ytDlpExecutable,
            @Value("${spanktube.archive.ffmpeg-executable:ffmpeg}") String ffmpegExecutable) {
        this.archiveProperties = archiveProperties;
        this.egressProperties = egressProperties;
        this.ytDlpExecutable = ytDlpExecutable;
        this.ffmpegExecutable = ffmpegExecutable;
    }

    long download(ArchiveModels.ExistingVideo metadata) {
        Path directory = Path.of(archiveProperties.root())
                .resolve(metadata.channelId())
                .resolve(metadata.videoId());
        try {
            Files.createDirectories(directory);
            Path output = directory.resolve("video.mkv");
            Files.deleteIfExists(output);
            List<String> command = new ArrayList<>(List.of(
                    ytDlpExecutable,
                    "--format", "bv*+ba",
                    "--merge-output-format", "mkv",
                    "--all-subs",
                    "--sub-langs", "all",
                    "--write-auto-sub",
                    "--embed-subs",
                    "--embed-chapters",
                    "--sponsorblock-mark", "all",
                    "--write-info-json",
                    "--write-thumbnail",
                    "--no-mtime",
                    "--no-playlist",
                    "--no-warnings",
                    "--socket-timeout", "30",
                    "--retries", "3",
                    "--sleep-requests", "1",
                    "--sleep-subtitles", "2",
                    "--output", directory.resolve("video.%(ext)s").toString()));
            if (egressProperties.mode() == EgressProperties.Mode.SOCKS5) {
                command.add("--proxy");
                command.add(proxyUrl());
            }
            command.add("https://www.youtube.com/watch?v=" + metadata.videoId());
            run(command, directory, archiveProperties.downloadTimeout());

            Path remux = directory.resolve("video.remux.mkv");
            run(List.of(ffmpegExecutable, "-y", "-i", output.toString(), "-map", "0",
                    "-c", "copy", "-c:s", "srt", "-map_chapters", "0", "-map_metadata", "0",
                    remux.toString()), directory, Duration.ofMinutes(15));
            Files.move(remux, output, StandardCopyOption.REPLACE_EXISTING);
            Path infoJson = directory.resolve("video.info.json");
            if (Files.exists(infoJson)) {
                Files.move(infoJson, directory.resolve("info.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.size(output);
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to write archive on NFS: " + error.getMessage(), error);
        }
    }

    void discard(ArchiveModels.ExistingVideo metadata) {
        Path root = Path.of(archiveProperties.root()).toAbsolutePath().normalize();
        Path directory = root.resolve(metadata.channelId()).resolve(metadata.videoId()).normalize();
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

    private void run(List<String> command, Path directory, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream(), executableName(command.getFirst()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ArchiveDownloadException("Process timed out: " + executableName(command.getFirst()));
            }
            String errorOutput = stderr.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ArchiveDownloadException(executableName(command.getFirst()) + " failed: " + sanitize(errorOutput));
            }
            stdout.get(2, TimeUnit.SECONDS);
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

    private CompletableFuture<String> readAsync(InputStream input, String executable) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))
                        .lines()
                        .peek(line -> org.slf4j.LoggerFactory.getLogger(YtDlpDownloader.class)
                                .info("{}: {}", executable, sanitize(line)))
                        .collect(java.util.stream.Collectors.joining("\n"));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private static CompletableFuture<String> readAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
    }
}
