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
    private final PlaybackLogService playbackLogs;
    private final Path archiveRoot;
    private final Path cacheRoot;
    private final String ffmpeg;
    private final Duration timeout;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    ArchivePlaybackService(CatalogArchiveClient catalog, PlaybackLogService playbackLogs,
            @Value("${spanktube.archive.root:/archive}") String archiveRoot,
            @Value("${spanktube.media.playback-cache:/tmp/spanktube-playback}") String cacheRoot,
            @Value("${spanktube.media.ffmpeg-executable:ffmpeg}") String ffmpeg,
            @Value("${spanktube.media.remux-timeout:30m}") Duration timeout) {
        this.catalog = catalog;
        this.playbackLogs = playbackLogs;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        this.ffmpeg = ffmpeg;
        this.timeout = timeout;
    }

    Mono<ResponseEntity<String>> manifest(String videoId, String username, String playbackSession, String versionId) {
        return catalog.archived(videoId, username, versionId)
                .flatMap(video -> Mono.fromCallable(() -> {
                    playbackLogs.log(videoId, playbackSession, "Найдена архивная копия; подготовка воспроизведения…");
                    prepare(video, playbackSession, versionId);
                    String manifest = Files.readString(cacheDirectory(videoId, versionId).resolve("manifest.mpd"));
                    String versionPath = versionId == null || versionId.isBlank() ? "" : versionId + "/";
                    String body = manifest.replace("<Period", "<BaseURL>/media/v/" + videoId
                            + "/archive/" + versionPath + "</BaseURL><Period");
                    return ResponseEntity.ok().contentType(DASH).cacheControl(CacheControl.noCache()).body(body);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    Mono<ResponseEntity<FileSystemResource>> file(String videoId, String username, String filename, String versionId) {
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found"));
        }
        return catalog.archived(videoId, username, versionId)
                .flatMap(video -> Mono.fromCallable(() -> {
                    Path file = cacheDirectory(videoId, versionId).resolve(filename).normalize();
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

    Mono<ResponseEntity<FileSystemResource>> archivedPlaylistThumbnail(String playlistId) {
        return Mono.fromCallable(() -> {
            Path file = archiveRoot.resolve("playlists").resolve(playlistId).resolve("thumbnail").normalize();
            if (!file.startsWith(archiveRoot)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist thumbnail not found");
            }
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(Files.size(file))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(new FileSystemResource(file));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void prepare(ArchivedVideo video, String playbackSession, String versionId) throws Exception {
        if (!video.videoId().matches("[A-Za-z0-9_-]{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid videoId");
        }
        Path source = archiveRoot.resolve(video.archivedPath()).normalize();
        if (!source.startsWith(archiveRoot) || !Files.isRegularFile(source)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archived file not found");
        }
        String cacheKey = cacheName(video.videoId(), versionId);
        Path output = cacheRoot.resolve(cacheKey).resolve("manifest.mpd");
        if (cacheReady(output, source)) {
            playbackLogs.log(video.videoId(), playbackSession, "Используется готовый архивный DASH");
            return;
        }
        Object lock = locks.computeIfAbsent(cacheKey, key -> new Object());
        Path building = cacheRoot.resolve(cacheKey + ".building");
        try {
            synchronized (lock) {
                if (cacheReady(output, source)) {
                    playbackLogs.log(video.videoId(), playbackSession, "Используется готовый архивный DASH");
                    return;
                }
                deleteDirectory(output.getParent());
                deleteDirectory(building);
                playbackLogs.log(video.videoId(), playbackSession,
                        "Преобразование MKV в DASH без перекодирования…");
                Files.createDirectories(building);
                Path buildingManifest = building.resolve("manifest.mpd");
                List<String> command = List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                        "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-f", "dash",
                        "-seg_duration", "4", "-use_template", "1", "-use_timeline", "1", buildingManifest.toString());
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                var outputReader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try (var input = process.getInputStream()) {
                        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException error) {
                        return error.getMessage();
                    }
                });
                try {
                    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        throw new IllegalStateException("Archive remux timed out");
                    }
                    if (process.exitValue() != 0) {
                        throw new IllegalStateException("Archive remux failed: " + outputReader.get(2, TimeUnit.SECONDS));
                    }
                } catch (Exception error) {
                    deleteDirectory(building);
                    throw error;
                }
                Files.createDirectories(output.getParent());
                try (var files = Files.list(building)) {
                    for (Path path : files.filter(Files::isRegularFile)
                            .filter(path -> !path.getFileName().toString().equals("manifest.mpd")).toList()) {
                        Files.move(path, output.getParent().resolve(path.getFileName().toString()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.move(buildingManifest, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                deleteDirectory(building);
                playbackLogs.log(video.videoId(), playbackSession, "Архивные сегменты подготовлены");
            }
        } finally {
            locks.remove(cacheKey, lock);
        }
    }

    static boolean cacheReady(Path output, Path source) throws IOException {
        if (!Files.isRegularFile(output)
                || Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(source)) < 0) {
            return false;
        }
        try (var files = Files.list(output.getParent())) {
            return files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new IllegalStateException("Unable to delete playback cache " + path, error);
                }
            });
        }
    }

    private Path cacheDirectory(String videoId, String versionId) {
        return cacheRoot.resolve(cacheName(videoId, versionId)).normalize();
    }

    private static String cacheName(String videoId, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return videoId;
        }
        try {
            java.util.UUID.fromString(versionId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid archive version");
        }
        return videoId + "-" + versionId;
    }
}
