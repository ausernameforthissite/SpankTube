package dev.spanktube.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
class ArchivePlaybackService {

    private static final MediaType DASH = MediaType.parseMediaType("application/dash+xml");

    private final CatalogArchiveClient catalog;
    private final Path archiveRoot;
    private final Path cacheRoot;
    private final String ffmpeg;
    private final Duration timeout;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    ArchivePlaybackService(CatalogArchiveClient catalog,
            @Value("${spanktube.archive.root:/archive}") String archiveRoot,
            @Value("${spanktube.media.playback-cache:/tmp/spanktube-playback}") String cacheRoot,
            @Value("${spanktube.media.ffmpeg-executable:ffmpeg}") String ffmpeg,
            @Value("${spanktube.media.remux-timeout:30m}") Duration timeout) {
        this.catalog = catalog;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        this.ffmpeg = ffmpeg;
        this.timeout = timeout;
    }

    Mono<ResponseEntity<String>> manifest(String videoId, String username) {
        return catalog.owned(videoId, username)
                .flatMap(video -> Mono.fromCallable(() -> {
                    prepare(video);
                    String manifest = Files.readString(cacheDirectory(videoId).resolve("manifest.mpd"));
                    String body = manifest.replace("<Period", "<BaseURL>/media/v/" + videoId
                            + "/archive/</BaseURL><Period");
                    return ResponseEntity.ok().contentType(DASH).cacheControl(CacheControl.noCache()).body(body);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    Mono<ResponseEntity<FileSystemResource>> file(String videoId, String username, String filename) {
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found"));
        }
        return catalog.owned(videoId, username)
                .flatMap(video -> Mono.fromCallable(() -> {
                    Path file = cacheDirectory(videoId).resolve(filename).normalize();
                    if (!file.startsWith(cacheRoot) || !Files.isRegularFile(file)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
                    }
                    MediaType type = filename.endsWith(".webm")
                            ? MediaType.parseMediaType("video/webm") : MediaType.APPLICATION_OCTET_STREAM;
                    return ResponseEntity.ok().contentType(type).contentLength(Files.size(file))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                            .body(new FileSystemResource(file));
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private void prepare(ArchivedVideo video) throws Exception {
        if (!video.videoId().matches("[A-Za-z0-9_-]{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid videoId");
        }
        Path source = archiveRoot.resolve(video.archivedPath()).normalize();
        if (!source.startsWith(archiveRoot) || !Files.isRegularFile(source)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archived file not found");
        }
        Path output = cacheDirectory(video.videoId()).resolve("manifest.mpd");
        if (Files.isRegularFile(output) && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return;
        }
        Object lock = locks.computeIfAbsent(video.videoId(), key -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(output)
                        && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(source)) >= 0) {
                    return;
                }
                Files.createDirectories(output.getParent());
                List<String> command = List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                        "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-f", "dash",
                        "-seg_duration", "4", "-use_template", "1", "-use_timeline", "1", output.toString());
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                var outputReader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try (var input = process.getInputStream()) {
                        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException error) {
                        return error.getMessage();
                    }
                });
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Archive remux timed out");
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("Archive remux failed: " + outputReader.get(2, TimeUnit.SECONDS));
                }
            }
        } finally {
            locks.remove(video.videoId(), lock);
        }
    }

    private Path cacheDirectory(String videoId) {
        return cacheRoot.resolve(videoId).normalize();
    }
}
