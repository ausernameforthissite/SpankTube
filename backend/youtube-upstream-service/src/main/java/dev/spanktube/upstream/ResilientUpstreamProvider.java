package dev.spanktube.upstream;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
class ResilientUpstreamProvider implements UpstreamProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientUpstreamProvider.class);
    private static final Duration CURSOR_TTL = Duration.ofMinutes(30);
    private static final int MAX_CURSORS = 512;

    private final NewPipeUpstreamProvider primary;
    private final KidsVideoClient kids;
    private final YtDlpUpstreamProvider fallback;
    private final LiveDashClient liveClient;
    private final ConcurrentHashMap<String, Continuation> continuations = new ConcurrentHashMap<>();

    ResilientUpstreamProvider(NewPipeUpstreamProvider primary, KidsVideoClient kids, YtDlpUpstreamProvider fallback,
            LiveDashClient liveClient) {
        this.primary = primary;
        this.kids = kids;
        this.fallback = fallback;
        this.liveClient = liveClient;
    }

    @Override
    public VideoDetails video(String videoId) {
        try {
            return primary.video(videoId);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (UpstreamException error) {
            if (error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                throw error;
            }
            if (error.reason() == UpstreamException.Reason.ANTIBOT) {
                try {
                    return primary.video(videoId);
                } catch (IllegalArgumentException invalidInput) {
                    throw invalidInput;
                } catch (UpstreamException retryError) {
                    if (retryError.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                        throw retryError;
                    }
                    LOGGER.warn("NewPipe video {} retry failed; trying VisionOS live client: {}",
                            videoId, retryError.getMessage());
                }
            } else {
                LOGGER.warn("NewPipe video {} failed; trying VisionOS live client: {}", videoId, error.getMessage());
            }
        }
        try {
            var details = liveClient.liveVideo(videoId);
            if (details.isPresent()) {
                LOGGER.info("Video {} uses VisionOS live playback", videoId);
                return details.get();
            }
        } catch (UpstreamException error) {
            if (error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                throw error;
            }
            LOGGER.warn("VisionOS live client {} failed; trying YouTube Kids client: {}", videoId, error.getMessage());
        }
        try {
            var details = kids.video(videoId);
            if (details.isPresent()) {
                LOGGER.info("Video {} identified as made for kids; using Android Reel client", videoId);
                return details.get();
            }
        } catch (UpstreamException error) {
            if (error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                throw error;
            }
            LOGGER.warn("YouTube Kids client {} failed; falling back to yt-dlp: {}", videoId, error.getMessage());
        }
        return fallback.video(videoId);
    }

    @Override
    public SearchResults search(String query, String type, String cursor) {
        return paged("search " + type + ":" + query, cursor,
                () -> primary.search(query, type, cursor),
                next -> fallback.search(query, type, next),
                results -> results.items().size(), SearchResults::nextCursor);
    }

    @Override
    public ChannelDetails channel(String channelId, String cursor) {
        return paged("channel " + channelId, cursor,
                () -> primary.channel(channelId, cursor),
                next -> fallback.channel(channelId, next),
                results -> results.videos().size(), ChannelDetails::nextCursor);
    }

    @Override
    public SearchResults channelVideos(String channelId, String cursor) {
        return paged("channel videos " + channelId, cursor,
                () -> primary.channelVideos(channelId, cursor),
                next -> fallback.channelVideos(channelId, next),
                results -> results.items().size(), SearchResults::nextCursor);
    }

    @Override
    public SearchResults channelLiveStreams(String channelId, String cursor) {
        SearchResults results = paged("channel live streams " + channelId, cursor,
                () -> primary.channelLiveStreams(channelId, cursor),
                next -> fallback.channelLiveStreams(channelId, next),
                page -> page.items().size(), SearchResults::nextCursor);
        return new SearchResults(results.items().stream().map(item -> "VIDEO_STREAM".equals(item.streamType())
                ? new SearchItem(item.kind(), item.id(), item.title(), item.url(), item.thumbnailUrl(), item.channelName(),
                        item.durationSeconds(), item.videoCount(), item.viewCount(), item.publishedAt(), "POST_LIVE_STREAM")
                : item).toList(), results.nextCursor());
    }

    @Override
    public SearchResults channelPlaylists(String channelId, String cursor) {
        return paged("channel playlists " + channelId, cursor,
                () -> primary.channelPlaylists(channelId, cursor),
                next -> fallback.channelPlaylists(channelId, next),
                results -> results.items().size(), SearchResults::nextCursor);
    }

    @Override
    public PlaylistDetails playlist(String playlistId, String cursor) {
        return paged("playlist " + playlistId, cursor,
                () -> primary.playlist(playlistId, cursor),
                next -> fallback.playlist(playlistId, next),
                results -> results.items().size(), PlaylistDetails::nextCursor);
    }

    private <T> T paged(String operation, String cursor, Supplier<T> primary, Function<String, T> fallback,
            Function<T, Integer> size, Function<T, String> nextCursor) {
        String fallbackCursor = fallbackCursor(operation, cursor);
        try {
            T result = primary.get();
            remember(operation, fallbackCursor, size.apply(result), nextCursor.apply(result));
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (UpstreamException error) {
            if (error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                throw error;
            }
            try {
                T result = primary.get();
                remember(operation, fallbackCursor, size.apply(result), nextCursor.apply(result));
                return result;
            } catch (IllegalArgumentException invalidInput) {
                throw invalidInput;
            } catch (UpstreamException retryError) {
                if (retryError.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                    throw retryError;
                }
                LOGGER.warn("NewPipe {} retry failed; falling back to yt-dlp: {}", operation, retryError.getMessage());
                try {
                    return fallback.apply(fallbackCursor);
                } catch (IllegalArgumentException fallbackError) {
                    if (fallbackCursor != null && fallbackCursor.startsWith("np:")) {
                        throw retryError;
                    }
                    throw fallbackError;
                }
            }
        }
    }

    private String fallbackCursor(String operation, String cursor) {
        if (cursor == null) {
            return null;
        }
        Continuation continuation = continuations.get(cursor);
        if (continuation != null && continuation.operation().equals(operation)
                && !expired(continuation, System.nanoTime())) {
            return continuation.cursor();
        }
        return cursor;
    }

    private void remember(String operation, String cursor, int size, String nextCursor) {
        if (nextCursor == null || !nextCursor.startsWith("np:")) {
            return;
        }
        int offset = offset(cursor);
        if (offset < 0) {
            return;
        }
        long now = System.nanoTime();
        continuations.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        continuations.put(nextCursor, new Continuation(operation, Integer.toString(offset + size), now));
        while (continuations.size() > MAX_CURSORS) {
            String eldest = continuations.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().createdAt(), right.getValue().createdAt()))
                    .map(Map.Entry::getKey).orElse(null);
            if (eldest == null) {
                return;
            }
            continuations.remove(eldest);
        }
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank() || "0".equals(cursor)) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            return offset >= 0 ? offset : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static boolean expired(Continuation continuation, long now) {
        return now - continuation.createdAt() >= CURSOR_TTL.toNanos();
    }

    private record Continuation(String operation, String cursor, long createdAt) {
    }
}
