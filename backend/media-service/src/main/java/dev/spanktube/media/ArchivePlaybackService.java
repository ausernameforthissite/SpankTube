package dev.spanktube.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
    private final OnDemandDashService onDemandDash;
    private final Path archiveRoot;
    private final Path cacheRoot;

    ArchivePlaybackService(CatalogArchiveClient catalog, PlaybackLogService playbackLogs,
            OnDemandDashService onDemandDash,
            @Value("${spanktube.archive.root:/archive}") String archiveRoot,
            @Value("${spanktube.media.playback-cache:/tmp/spanktube-playback}") String cacheRoot) {
        this.catalog = catalog;
        this.playbackLogs = playbackLogs;
        this.onDemandDash = onDemandDash;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
    }

    Mono<ResponseEntity<String>> manifest(String videoId, String username, String playbackSession, String versionId) {
        return catalog.archived(videoId, username, versionId)
                .flatMap(video -> Mono.fromCallable(() -> {
                    playbackLogs.log(videoId, playbackSession, "Найдена архивная копия; подготовка воспроизведения…");
                    prepare(video, playbackSession, versionId);
                    String manifest = Files.readString(cacheDirectory(videoId, versionId).resolve("ondemand.mpd"));
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
                    FileSystemResource file = onDemandDash.segment(archivedSource(video),
                            cacheDirectory(videoId, versionId), videoId, filename, null);
                    MediaType type = filename.endsWith(".webm") ? MediaType.parseMediaType("video/webm")
                            : filename.endsWith(".m4s") ? MediaType.parseMediaType("video/mp4")
                            : filename.endsWith(".vtt") ? MediaType.parseMediaType("text/vtt")
                            : MediaType.APPLICATION_OCTET_STREAM;
                    return ResponseEntity.ok().contentType(type).contentLength(Files.size(file.getFile().toPath()))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1))).body(file);
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
        onDemandDash.prepare(archivedSource(video), cacheDirectory(video.videoId(), versionId),
                video.videoId(), playbackSession);
    }

    private Path archivedSource(ArchivedVideo video) {
        Path source = archiveRoot.resolve(video.archivedPath()).normalize();
        if (!source.startsWith(archiveRoot) || !Files.isRegularFile(source)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archived file not found");
        }
        return source;
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
