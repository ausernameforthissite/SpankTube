package dev.spanktube.upstream;

import java.util.List;

public record VideoDetails(
        String videoId,
        String title,
        String description,
        String channelId,
        String channelName,
        Long durationSeconds,
        Long viewCount,
        Long likeCount,
        String publishedAt,
        List<StreamFormat> formats,
        List<SubtitleTrack> subtitles) {
}
