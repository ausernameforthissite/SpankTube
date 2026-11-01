package dev.spanktube.catalog;

public record ArchiveCompletedEvent(
        String ownerUsername,
        String channelId,
        String channelName,
        String channelHandle,
        String channelCustomPath,
        String channelAvatarUrl,
        Long channelSubscriberCount,
        String videoId,
        String title,
        String publishedAt,
        Long durationSeconds,
        String archivedPath,
        String quality,
        long sizeBytes,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        String description,
        Long audioBitrate) {

    public ArchiveCompletedEvent(String ownerUsername, String channelId, String channelName, String channelHandle,
            String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
            String videoId, String title, String publishedAt, Long durationSeconds,
            String archivedPath, String quality, long sizeBytes,
            Integer width, Integer height, String videoCodec, String audioCodec) {
        this(ownerUsername, channelId, channelName, channelHandle, channelCustomPath, channelAvatarUrl,
                channelSubscriberCount, videoId, title, publishedAt, durationSeconds, archivedPath, quality,
                sizeBytes, width, height, videoCodec, audioCodec, null, null);
    }

    public ArchiveCompletedEvent(String ownerUsername, String channelId, String channelName, String channelHandle,
            String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
            String videoId, String title, String publishedAt, Long durationSeconds,
            String archivedPath, String quality, long sizeBytes) {
        this(ownerUsername, channelId, channelName, channelHandle, channelCustomPath, channelAvatarUrl,
                channelSubscriberCount, videoId, title, publishedAt, durationSeconds, archivedPath, quality,
                sizeBytes, null, null, null, null, null, null);
    }
}
