package dev.spanktube.upstream;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class VideoService {

    private static final Duration CACHE_TTL = Duration.ofHours(5);
    private static final Duration LIVE_CACHE_TTL = Duration.ofSeconds(30);
    private static final String VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{11}";

    private final UpstreamProvider provider;
    private final MetadataCacheService cache;

    VideoService(UpstreamProvider provider, MetadataCacheService cache) {
        this.provider = provider;
        this.cache = cache;
    }

    public Mono<VideoDetails> video(String videoId) {
        validateVideoId(videoId);
        return cache.get("streams:v3", videoId, VideoDetails.class, () -> provider.video(videoId),
                details -> details.isLive() ? LIVE_CACHE_TTL : CACHE_TTL);
    }

    private static void validateVideoId(String videoId) {
        if (videoId == null || !videoId.matches(VIDEO_ID_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId must contain 11 YouTube ID characters");
        }
    }
}
