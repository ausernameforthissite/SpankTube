package dev.spanktube.media;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.spanktube.egress.EgressProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
class ThumbnailService {

    private final WebClient youtubeClient;
    private final EgressProbe egressProbe;
    private final ImageCache cache;
    private final ConcurrentHashMap<String, Mono<ResponseEntity<byte[]>>> inFlight = new ConcurrentHashMap<>();

    ThumbnailService(@Qualifier("youtubeWebClient") WebClient youtubeClient,
            ObjectProvider<EgressProbe> egressProbe, ImageCache cache) {
        this.youtubeClient = youtubeClient;
        this.egressProbe = egressProbe.getIfAvailable();
        this.cache = cache;
    }

    Mono<ResponseEntity<byte[]>> videoThumbnail(String videoId, String quality) {
        return image(thumbnailUrls(videoId, quality).toArray(String[]::new));
    }

    Mono<ResponseEntity<byte[]>> image(String url) {
        if (url == null || url.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
        }
        return image(new String[] { url });
    }

    private Mono<ResponseEntity<byte[]>> image(String[] urls) {
        return fetch(urls, 0);
    }

    private Mono<ResponseEntity<byte[]>> fetch(String[] urls, int index) {
        if (index >= urls.length) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
        }
        String url = MediaUrlValidator.thumbnailUrl(urls[index]).toString();
        ImageCache.CachedImage cached = cache.get(url);
        if (cached != null) {
            return Mono.just(response(cached.body(), cached.contentType()));
        }
        if (egressProbe != null && !egressProbe.isAvailable()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "YouTube egress is unavailable: " + egressProbe.lastError()));
        }
        return inFlight.computeIfAbsent(url, ignored -> youtubeClient.get()
                .uri(url)
                .retrieve()
                .toEntity(byte[].class)
                .timeout(Duration.ofSeconds(8))
                .flatMap(response -> {
                    byte[] body = response.getBody();
                    if (body == null || body.length == 0) {
                        return fetch(urls, index + 1);
                    }
                    MediaType contentType = response.getHeaders().getContentType() == null
                            ? MediaType.IMAGE_JPEG : response.getHeaders().getContentType();
                    cache.put(url, body, contentType);
                    return Mono.just(response(body, contentType));
                })
                .onErrorResume(ThumbnailService::canTryAnotherVariant, error -> fetch(urls, index + 1))
                .doFinally(signal -> inFlight.remove(url))
                .cache());
    }

    private static boolean canTryAnotherVariant(Throwable error) {
        if (error instanceof WebClientResponseException.NotFound
                || error instanceof WebClientResponseException.Gone
                || error instanceof TimeoutException || error instanceof java.io.IOException) {
            return true;
        }
        return error instanceof WebClientResponseException response
                && (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError());
    }

    static List<String> thumbnailUrls(String videoId, String quality) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid videoId");
        }
        String prefix = "https://i.ytimg.com/vi/" + videoId + "/";
        if ("max".equals(quality)) {
            return List.of(prefix + "maxresdefault.jpg", prefix + "sddefault.jpg",
                    prefix + "hqdefault.jpg", prefix + "mqdefault.jpg", prefix + "default.jpg");
        }
        return List.of(prefix + "hqdefault.jpg", prefix + "mqdefault.jpg", prefix + "default.jpg");
    }

    private static ResponseEntity<byte[]> response(byte[] body, MediaType contentType) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(body.length)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                .body(body);
    }
}
