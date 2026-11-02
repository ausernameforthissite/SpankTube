package dev.spanktube.upstream;

import java.util.List;

public record PublicPlaylist(
        String playlistId,
        String title,
        PublicChannel channel,
        List<SearchItem> items,
        String nextCursor,
        String thumbnailUrl,
        Long videoCount,
        Integer totalPages) {

    static PublicPlaylist from(PlaylistDetails details) {
        PublicChannel channel = new PublicChannel(details.channelId(), details.channelName(), details.channelHandle(),
                details.channelCustomPath(), details.channelCustomPath() != null ? "/c/" + details.channelCustomPath()
                : details.channelHandle() != null ? "/@" + details.channelHandle() : "/channel/" + details.channelId(),
                MediaLinks.thumbnail(details.channelAvatarUrl(),
                        details.channelId() == null ? null : "/media/channel/" + details.channelId() + "/avatar"),
                details.channelSubscriberCount(), List.of(), List.of(), null, null, null);
        Integer totalPages = details.videoCount() == null || details.pageSize() == null || details.pageSize() <= 0
                ? null : (int) Math.max(1, (details.videoCount() + details.pageSize() - 1) / details.pageSize());
        return new PublicPlaylist(details.playlistId(), details.title(), channel, details.items(), details.nextCursor(),
                MediaLinks.thumbnail(details.thumbnailUrl(), "/media/playlist/" + details.playlistId() + "/thumbnail"),
                details.videoCount(), totalPages);
    }
}
