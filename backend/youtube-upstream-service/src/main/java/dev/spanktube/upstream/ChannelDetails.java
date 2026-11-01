package dev.spanktube.upstream;

import java.util.List;

public record ChannelDetails(
        String channelId,
        String title,
        String handle,
        String customPath,
        String avatarUrl,
        Long subscriberCount,
        List<SearchItem> videos,
        List<SearchItem> playlists,
        String nextCursor) {
}
