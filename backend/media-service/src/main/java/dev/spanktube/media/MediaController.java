package dev.spanktube.media;

import java.net.URI;
import java.util.List;

import dev.spanktube.egress.EgressProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
class MediaController {

    private static final MediaType DASH = MediaType.parseMediaType("application/dash+xml");
    private static final MediaType VTT = MediaType.parseMediaType("text/vtt");
    private static final List<String> REQUEST_HEADERS = List.of(
            HttpHeaders.RANGE, HttpHeaders.IF_RANGE, HttpHeaders.IF_NONE_MATCH, HttpHeaders.IF_MODIFIED_SINCE);
    private static final List<String> RESPONSE_HEADERS = List.of(
            HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE, HttpHeaders.ACCEPT_RANGES,
            HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.CACHE_CONTROL);

    private final UpstreamClient upstreamClient;
    private final Mp4IndexService mp4IndexService;
    private final DashManifestGenerator manifestGenerator;
    private final WebClient youtubeClient;
    private final EgressProbe egressProbe;
    private final ArchivePlaybackService archivePlayback;
    private final ThumbnailService thumbnailService;
    private final PlaybackLogService playbackLogs;

    MediaController(UpstreamClient upstreamClient, Mp4IndexService mp4IndexService,
            DashManifestGenerator manifestGenerator, @Qualifier("youtubeWebClient") WebClient youtubeClient,
            ObjectProvider<EgressProbe> egressProbe, ArchivePlaybackService archivePlayback,
            ThumbnailService thumbnailService, PlaybackLogService playbackLogs) {
        this.upstreamClient = upstreamClient;
        this.mp4IndexService = mp4IndexService;
        this.manifestGenerator = manifestGenerator;
        this.youtubeClient = youtubeClient;
        this.egressProbe = egressProbe.getIfAvailable();
        this.archivePlayback = archivePlayback;
        this.thumbnailService = thumbnailService;
        this.playbackLogs = playbackLogs;
    }

    @GetMapping(value = "/media/v/{videoId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<org.springframework.http.codec.ServerSentEvent<String>> logs(@PathVariable String videoId,
            @RequestParam String session) {
        return playbackLogs.stream(videoId, session);
    }

    @GetMapping("/media/v/{videoId}/manifest.mpd")
    Mono<ResponseEntity<String>> manifest(@PathVariable String videoId, @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String playbackSession, @RequestParam(required = false) String version) {
        playbackLogs.log(videoId, playbackSession, "Проверка архивной копии SpankTube…");
        return archivePlayback.manifest(videoId, headers.getFirst("X-SpankTube-User"), playbackSession, version)
                .switchIfEmpty(Mono.defer(() -> {
                    playbackLogs.log(videoId, playbackSession, "Архивная копия не найдена; запрос форматов YouTube…");
                    return upstreamClient.video(videoId)
                            .doOnNext(video -> playbackLogs.log(videoId, playbackSession,
                                    "Получено форматов: " + video.formats().size()))
                            .flatMap(video -> mp4IndexService.enrich(video, playbackSession))
                            .map(manifestGenerator::generate)
                            .map(body -> ResponseEntity.ok()
                                    .contentType(DASH)
                                    .cacheControl(CacheControl.noCache())
                                    .body(body));
                }))
                .doOnNext(response -> playbackLogs.log(videoId, playbackSession, "DASH-манифест готов"))
                .doOnError(error -> playbackLogs.error(videoId, playbackSession, error));
    }

    @GetMapping("/media/v/{videoId}/archive/{filename}")
    Mono<ResponseEntity<FileSystemResource>> archivedSegment(@PathVariable String videoId,
            @PathVariable String filename, @RequestHeader HttpHeaders headers,
            @RequestParam(required = false) String version) {
        return archivePlayback.file(videoId, headers.getFirst("X-SpankTube-User"), filename, version)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive not found")));
    }

    @GetMapping("/media/v/{videoId}/archive/{versionId}/{filename}")
    Mono<ResponseEntity<FileSystemResource>> archivedVersionSegment(@PathVariable String videoId,
            @PathVariable String versionId, @PathVariable String filename, @RequestHeader HttpHeaders headers) {
        return archivePlayback.file(videoId, headers.getFirst("X-SpankTube-User"), filename, versionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive not found")));
    }

    @GetMapping("/media/v/{videoId}/seg/{formatId}")
    Mono<ResponseEntity<Flux<DataBuffer>>> segment(@PathVariable String videoId,
            @PathVariable String formatId, @RequestHeader HttpHeaders requestHeaders) {
        return upstreamClient.video(videoId)
                .flatMap(video -> video.formats().stream()
                        .filter(format -> format.formatId().equals(formatId))
                        .findFirst()
                        .map(format -> proxy(MediaUrlValidator.mediaUrl(format.url()), requestHeaders,
                                format.mimeType()))
                        .orElseGet(() -> Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Unknown format " + formatId))));
    }

    @GetMapping("/media/v/{videoId}/sub/{trackId}")
    Mono<ResponseEntity<Flux<DataBuffer>>> subtitle(@PathVariable String videoId,
            @PathVariable String trackId, @RequestHeader HttpHeaders requestHeaders) {
        return upstreamClient.video(videoId)
                .flatMap(video -> video.subtitles().stream()
                        .filter(track -> track.trackId().equals(trackId))
                        .findFirst()
                        .map(track -> proxy(MediaUrlValidator.subtitleUrl(track.url()), requestHeaders,
                                VTT.toString()))
                        .orElseGet(() -> Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Unknown subtitle " + trackId))));
    }

    @GetMapping("/media/v/{videoId}/thumbnail")
    Mono<ResponseEntity<byte[]>> videoThumbnail(@PathVariable String videoId,
            @RequestParam(defaultValue = "hq") String quality) {
        return thumbnailService.videoThumbnail(videoId, quality);
    }

    @GetMapping("/media/channel/{channelId}/avatar")
    Mono<ResponseEntity<byte[]>> channelAvatar(@PathVariable String channelId) {
        return upstreamClient.avatar(channelId).flatMap(thumbnailService::image);
    }

    @GetMapping("/media/playlist/{playlistId}/thumbnail")
    Mono<ResponseEntity<byte[]>> playlistThumbnail(@PathVariable String playlistId) {
        return upstreamClient.playlistThumbnail(playlistId).flatMap(thumbnailService::image);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> proxy(URI uri, HttpHeaders incoming, String fallbackType) {
        if (egressProbe != null && !egressProbe.isAvailable()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "YouTube egress is unavailable: " + egressProbe.lastError()));
        }
        return youtubeClient.get()
                .uri(uri)
                .headers(outgoing -> copyHeaders(incoming, outgoing, REQUEST_HEADERS))
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .map(response -> {
                    HttpHeaders outgoing = new HttpHeaders();
                    copyHeaders(response.getHeaders(), outgoing, RESPONSE_HEADERS);
                    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE) != null
                            ? response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)
                            : fallbackType;
                    outgoing.set(HttpHeaders.CONTENT_TYPE, contentType);
                    return new ResponseEntity<>(response.getBody(), outgoing, response.getStatusCode());
                });
    }

    private static void copyHeaders(HttpHeaders source, HttpHeaders target, List<String> names) {
        for (String name : names) {
            String value = source.getFirst(name);
            if (value != null) {
                target.set(name, value);
            }
        }
    }
}
