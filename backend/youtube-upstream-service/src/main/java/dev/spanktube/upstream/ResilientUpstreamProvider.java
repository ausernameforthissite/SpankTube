package dev.spanktube.upstream;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
class ResilientUpstreamProvider implements UpstreamProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientUpstreamProvider.class);
    private final NewPipeUpstreamProvider primary;
    private final YtDlpUpstreamProvider fallback;

    ResilientUpstreamProvider(NewPipeUpstreamProvider primary, YtDlpUpstreamProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public VideoDetails video(String videoId) {
        return execute("video " + videoId, () -> primary.video(videoId), () -> fallback.video(videoId));
    }

    @Override
    public SearchResults search(String query, String type, String cursor) {
        return execute("search " + type, () -> primary.search(query, type, cursor),
                () -> fallback.search(query, type, cursor));
    }

    @Override
    public ChannelDetails channel(String channelId, String cursor) {
        return execute("channel " + channelId, () -> primary.channel(channelId, cursor),
                () -> fallback.channel(channelId, cursor));
    }

    @Override
    public SearchResults channelVideos(String channelId, String cursor) {
        return execute("channel videos " + channelId, () -> primary.channelVideos(channelId, cursor),
                () -> fallback.channelVideos(channelId, cursor));
    }

    @Override
    public SearchResults channelPlaylists(String channelId, String cursor) {
        return execute("channel playlists " + channelId, () -> primary.channelPlaylists(channelId, cursor),
                () -> fallback.channelPlaylists(channelId, cursor));
    }

    @Override
    public PlaylistDetails playlist(String playlistId, String cursor) {
        return execute("playlist " + playlistId, () -> primary.playlist(playlistId, cursor),
                () -> fallback.playlist(playlistId, cursor));
    }

    private static <T> T execute(String operation, Supplier<T> primary, Supplier<T> fallback) {
        try {
            return primary.get();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (UpstreamException error) {
            if (error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE) {
                throw error;
            }
            LOGGER.warn("NewPipe {} failed; falling back to yt-dlp: {}", operation, error.getMessage());
            return fallback.get();
        }
    }
}
