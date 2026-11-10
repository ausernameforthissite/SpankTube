package dev.spanktube.upstream;

public record SearchItem(
        String kind,
        String id,
        String title,
        String url,
        String thumbnailUrl,
        String channelName,
        Long durationSeconds,
        Integer videoCount,
        Long viewCount,
        String publishedAt,
        String streamType) {

    public SearchItem(String kind, String id, String title, String url, String thumbnailUrl, String channelName,
            Long durationSeconds, Integer videoCount, Long viewCount, String publishedAt) {
        this(kind, id, title, url, thumbnailUrl, channelName, durationSeconds, videoCount, viewCount, publishedAt,
                "VIDEO_STREAM");
    }

    @Override
    public String streamType() {
        return streamType == null ? "VIDEO_STREAM" : streamType;
    }
}
