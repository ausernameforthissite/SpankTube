package dev.spanktube.upstream;

import java.time.Duration;
import java.util.Locale;

import dev.spanktube.egress.EgressProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Service
public class VideoService {

    private static final Duration CACHE_TTL = Duration.ofHours(5);
    private static final Duration LIVE_CACHE_TTL = Duration.ofSeconds(30);
    private static final String VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{11}";

    private final UpstreamProvider provider;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EgressProperties egressProperties;

    VideoService(UpstreamProvider provider, ReactiveStringRedisTemplate redis,
            ObjectMapper objectMapper, EgressProperties egressProperties) {
        this.provider = provider;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.egressProperties = egressProperties;
    }

    public Mono<VideoDetails> video(String videoId) {
        validateVideoId(videoId);
        String key = cacheKey(videoId);
        return redis.opsForValue().get(key)
                .map(json -> objectMapper.readValue(json, VideoDetails.class))
                .switchIfEmpty(Mono.defer(() -> loadAndCache(videoId, key)));
    }

    private Mono<VideoDetails> loadAndCache(String videoId, String key) {
        return Mono.fromCallable(() -> provider.video(videoId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(details -> Mono.fromCallable(() -> objectMapper.writeValueAsString(details))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(json -> redis.opsForValue().set(key, json,
                                details.isLive() ? LIVE_CACHE_TTL : CACHE_TTL).thenReturn(details)));
    }

    private String cacheKey(String videoId) {
        String mode = egressProperties.mode().name().toLowerCase(Locale.ROOT);
        return "spanktube:streams:v3:" + mode + ":" + videoId;
    }

    private static void validateVideoId(String videoId) {
        if (videoId == null || !videoId.matches(VIDEO_ID_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId must contain 11 YouTube ID characters");
        }
    }
}
