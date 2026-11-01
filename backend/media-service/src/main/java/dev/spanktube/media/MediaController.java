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

    MediaController(UpstreamClient upstreamClient, Mp4IndexService mp4IndexService,
            DashManifestGenerator manifestGenerator, @Qualifier("youtubeWebClient") WebClient youtubeClient,
            ObjectProvider<EgressProbe> egressProbe, ArchivePlaybackService archivePlayback) {
        this.upstreamClient = upstreamClient;
        this.mp4IndexService = mp4IndexService;
        this.manifestGenerator = manifestGenerator;
        this.youtubeClient = youtubeClient;
        this.egressProbe = egressProbe.getIfAvailable();
        this.archivePlayback = archivePlayback;
    }

    @GetMapping("/media/v/{videoId}/manifest.mpd")
    Mono<ResponseEntity<String>> manifest(@PathVariable String videoId, @RequestHeader HttpHeaders headers) {
        return archivePlayback.manifest(videoId, headers.getFirst("X-SpankTube-User"))
                .switchIfEmpty(Mono.defer(() -> upstreamClient.video(videoId)
                        .flatMap(mp4IndexService::enrich)
                        .map(manifestGenerator::generate)
                        .map(body -> ResponseEntity.ok()
                                .contentType(DASH)
                                .cacheControl(CacheControl.noCache())
                                .body(body))));
    }

    @GetMapping("/media/v/{videoId}/archive/{filename}")
    Mono<ResponseEntity<FileSystemResource>> archivedSegment(@PathVariable String videoId,
            @PathVariable String filename, @RequestHeader HttpHeaders headers) {
        return archivePlayback.file(videoId, headers.getFirst("X-SpankTube-User"), filename)
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
    Mono<ResponseEntity<byte[]>> videoThumbnail(@PathVariable String videoId) {
        return upstreamClient.thumbnail(videoId).flatMap(this::image);
    }

    @GetMapping("/media/channel/{channelId}/avatar")
    Mono<ResponseEntity<byte[]>> channelAvatar(@PathVariable String channelId) {
        return upstreamClient.avatar(channelId).flatMap(this::image);
    }

    @GetMapping("/media/playlist/{playlistId}/thumbnail")
    Mono<ResponseEntity<byte[]>> playlistThumbnail(@PathVariable String playlistId) {
        return upstreamClient.playlistThumbnail(playlistId).flatMap(this::image);
    }

    private Mono<ResponseEntity<byte[]>> image(String url) {
        if (url == null || url.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
        }
        if (egressProbe != null && !egressProbe.isAvailable()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "YouTube egress is unavailable: " + egressProbe.lastError()));
        }
        return youtubeClient.get()
                .uri(MediaUrlValidator.thumbnailUrl(url))
                .retrieve()
                .toEntity(byte[].class)
                .map(response -> ResponseEntity.status(response.getStatusCode())
                        .contentType(response.getHeaders().getContentType())
                        .cacheControl(CacheControl.maxAge(1, java.util.concurrent.TimeUnit.HOURS))
                        .body(response.getBody()));
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
