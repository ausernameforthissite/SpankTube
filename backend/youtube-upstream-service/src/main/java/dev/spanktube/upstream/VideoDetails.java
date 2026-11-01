package dev.spanktube.upstream;

import java.util.List;

public record VideoDetails(
        String videoId,
        String title,
        String description,
        String channelId,
        String channelName,
        String channelHandle,
        String channelCustomPath,
        String channelAvatarUrl,
        Long channelSubscriberCount,
        Long durationSeconds,
        Long viewCount,
        Long likeCount,
        String publishedAt,
        String thumbnailUrl,
        List<StreamFormat> formats,
        List<SubtitleTrack> subtitles) {
}
