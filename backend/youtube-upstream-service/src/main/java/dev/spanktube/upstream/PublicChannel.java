package dev.spanktube.upstream;

import java.util.List;

public record PublicChannel(
        String channelId,
        String title,
        String handle,
        String customPath,
        String url,
        String avatarUrl,
        Long subscriberCount,
        List<SearchItem> videos,
        List<SearchItem> playlists,
        String nextCursor) {

    static PublicChannel from(ChannelDetails details) {
        String url = details.customPath() != null ? "/c/" + details.customPath()
                : details.handle() != null ? "/@" + details.handle()
                : "/channel/" + details.channelId();
        return new PublicChannel(details.channelId(), details.title(), details.handle(), details.customPath(), url,
                "/media/channel/" + details.channelId() + "/avatar", details.subscriberCount(),
                details.videos(), details.playlists(), details.nextCursor());
    }
}
