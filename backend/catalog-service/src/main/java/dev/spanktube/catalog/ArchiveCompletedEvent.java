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
        long sizeBytes) {
}
