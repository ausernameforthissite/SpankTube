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
        List<SubtitleTrack> subtitles,
        List<VideoChapter> chapters,
        Boolean madeForKids,
        String streamType,
        String liveDashUrl,
        String liveHlsUrl) {

    public VideoDetails(String videoId, String title, String description, String channelId, String channelName,
            String channelHandle, String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
            Long durationSeconds, Long viewCount, Long likeCount, String publishedAt, String thumbnailUrl,
            List<StreamFormat> formats, List<SubtitleTrack> subtitles, List<VideoChapter> chapters,
            Boolean madeForKids) {
        this(videoId, title, description, channelId, channelName, channelHandle, channelCustomPath, channelAvatarUrl,
                channelSubscriberCount, durationSeconds, viewCount, likeCount, publishedAt, thumbnailUrl, formats,
                subtitles, chapters, madeForKids, "VIDEO_STREAM", null, null);
    }

    @Override
    public String streamType() {
        return streamType == null ? "VIDEO_STREAM" : streamType;
    }

    public boolean isLive() {
        return "LIVE_STREAM".equals(streamType()) || "AUDIO_LIVE_STREAM".equals(streamType());
    }
}
