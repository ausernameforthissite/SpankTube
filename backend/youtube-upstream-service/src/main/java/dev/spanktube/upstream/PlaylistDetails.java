package dev.spanktube.upstream;

import java.util.List;

public record PlaylistDetails(
        String playlistId,
        String title,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String channelHandle,
        String channelCustomPath,
        Long channelSubscriberCount,
        List<SearchItem> items,
        String nextCursor,
        String thumbnailUrl) {
}
