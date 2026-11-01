package dev.spanktube.archive;

import java.util.List;

final class ArchiveModels {

    private ArchiveModels() {
    }

    enum Kind { VIDEO, PLAYLIST, CHANNEL }

    record Job(String id, Kind kind, String targetId, String ownerUsername, String ownerRole,
            int priority, String status, int totalCount, int completedCount, int failedCount,
            String createdAt, String startedAt, String finishedAt, String nextAttemptAt, String error) {
    }

    record JobItem(long id, String jobId, String videoId, String status,
            int attempts, String nextAttemptAt, String error) {
    }

    record EnqueueRequest(String owner, String role, Kind kind, String targetId, List<String> videoIds) {
    }

    record ExistingVideo(String ownerUsername, String channelId, String channelName, String channelHandle,
            String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
            String videoId, String title, String publishedAt, Long durationSeconds,
            String archivedPath, String quality, long sizeBytes) {
    }
}
