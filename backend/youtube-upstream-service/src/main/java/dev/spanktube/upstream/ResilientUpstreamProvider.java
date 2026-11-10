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
    private final KidsVideoClient kids;
    private final YtDlpUpstreamProvider fallback;
    private final LiveDashClient liveClient;

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
            LOGGER.warn("NewPipe video {} failed; trying VisionOS live client: {}", videoId, error.getMessage());
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
    public SearchResults channelLiveStreams(String channelId, String cursor) {
        return execute("channel live streams " + channelId, () -> primary.channelLiveStreams(channelId, cursor),
                () -> fallback.channelLiveStreams(channelId, cursor));
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
