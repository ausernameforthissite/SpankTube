package dev.spanktube.media;

import java.nio.file.Files;
import java.time.Duration;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class LocalFileController {

    private static final MediaType DASH = MediaType.parseMediaType("application/dash+xml");

    private final LocalFileAccessClient access;
    private final LocalFileService files;
    private final LocalFilePlaybackService playback;

    LocalFileController(LocalFileAccessClient access, LocalFileService files, LocalFilePlaybackService playback) {
        this.access = access;
        this.files = files;
        this.playback = playback;
    }

    @GetMapping("/api/v1/files/entry")
    Mono<LocalFileService.LocalFileEntry> entry(@RequestParam(defaultValue = "") String path,
            @RequestHeader HttpHeaders headers) {
        return access.check(headers)
                .then(Mono.fromCallable(() -> files.open(path)).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/api/v1/files")
    Mono<LocalFileService.LocalDirectory> browse(@RequestParam(defaultValue = "") String path,
            @RequestHeader HttpHeaders headers) {
        return access.check(headers)
                .then(Mono.fromCallable(() -> files.browse(path)).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/media/files/{fileId}/manifest.mpd")
    Mono<ResponseEntity<String>> manifest(@PathVariable String fileId, @RequestHeader HttpHeaders headers) {
        return access.check(headers)
                .then(Mono.fromCallable(() -> ResponseEntity.ok()
                        .contentType(DASH)
                        .cacheControl(CacheControl.noCache())
                        .body(playback.manifest(fileId)))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/media/files/{fileId}/segments/{filename}")
    Mono<ResponseEntity<FileSystemResource>> segment(@PathVariable String fileId, @PathVariable String filename,
            @RequestHeader HttpHeaders headers) {
        return access.check(headers)
                .then(Mono.fromCallable(() -> {
                    FileSystemResource file = playback.segment(fileId, filename);
                    MediaType type = filename.endsWith(".vtt") ? MediaType.parseMediaType("text/vtt")
                            : filename.endsWith(".m4s") ? MediaType.parseMediaType("video/mp4")
                            : MediaType.APPLICATION_OCTET_STREAM;
                    return ResponseEntity.ok()
                            .contentType(type)
                            .contentLength(Files.size(file.getFile().toPath()))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                            .body(file);
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
