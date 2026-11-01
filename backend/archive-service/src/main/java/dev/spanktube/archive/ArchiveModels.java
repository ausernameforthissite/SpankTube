package dev.spanktube.archive;

import java.util.List;

final class ArchiveModels {

    private ArchiveModels() {
    }

    enum Kind { VIDEO, PLAYLIST, CHANNEL, DOWNLOAD, REFRESH }

    enum Purpose { ARCHIVE, DOWNLOAD }

    enum Stage {
        QUEUED, WAITING, METADATA, DOWNLOADING, DOWNLOADING_VIDEO, DOWNLOADING_AUDIO,
        SUBTITLES, SPONSORBLOCK, MUXING, CHAPTERS, FINISHING, COMPLETED, FAILED, EXPIRED
    }

    record DownloadOptions(String videoFormatId, String audioFormatId, boolean subtitles, boolean sponsorBlock) {
        static DownloadOptions archiveDefaults() {
            return new DownloadOptions(null, null, true, true);
        }

        boolean audioOnly() {
            return videoFormatId == null && audioFormatId != null;
        }

        boolean videoOnly() {
            return videoFormatId != null && audioFormatId == null;
        }

        boolean archiveEquivalent() {
            return videoFormatId == null && audioFormatId == null && subtitles && sponsorBlock;
        }

        int stageCount() {
            int count = 3;
            if (!audioOnly()) count++;
            if (!videoOnly()) count++;
            if (subtitles) count++;
            if (sponsorBlock) count += 2;
            return count;
        }

        String signature() {
            return String.valueOf(videoFormatId) + "|" + audioFormatId + "|" + subtitles + "|" + sponsorBlock;
        }
    }

    record Job(String id, Kind kind, String targetId, String ownerUsername, String ownerRole,
            int priority, String status, int totalCount, int completedCount, int failedCount,
            String createdAt, String startedAt, String finishedAt, String nextAttemptAt, String error,
            Purpose purpose, Stage stage, long bytesDownloaded, long totalBytes, long speedBps,
            String downloadUrl, String title, Integer width, Integer height,
            String videoCodec, String audioCodec, DownloadOptions options,
            String currentVideoId, String fileName, String updatedAt, Long audioBitrate) {

        Job(String id, Kind kind, String targetId, String ownerUsername, String ownerRole,
                int priority, String status, int totalCount, int completedCount, int failedCount,
                String createdAt, String startedAt, String finishedAt, String nextAttemptAt, String error) {
            this(id, kind, targetId, ownerUsername, ownerRole, priority, status, totalCount, completedCount,
                    failedCount, createdAt, startedAt, finishedAt, nextAttemptAt, error,
                    Purpose.ARCHIVE, Stage.QUEUED, 0, 0, 0, null, targetId, null, null, null, null,
                    DownloadOptions.archiveDefaults(), null, null, null, null);
        }
    }

    record JobItem(long id, String jobId, String videoId, String status,
            int attempts, String nextAttemptAt, String error) {
    }

    record EnqueueRequest(String owner, String role, Kind kind, String targetId, List<String> videoIds,
            Purpose purpose, DownloadOptions options) {
        EnqueueRequest(String owner, String role, Kind kind, String targetId, List<String> videoIds) {
            this(owner, role, kind, targetId, videoIds, Purpose.ARCHIVE, DownloadOptions.archiveDefaults());
        }

        EnqueueRequest {
            if (purpose == null) {
                purpose = Purpose.ARCHIVE;
            }
            if (options == null) {
                options = DownloadOptions.archiveDefaults();
            }
            videoIds = List.copyOf(videoIds);
        }
    }

    record EnqueueResult(Job job, boolean created) {
    }

    record ExistingVideo(String ownerUsername, String channelId, String channelName, String channelHandle,
            String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
            String videoId, String title, String publishedAt, Long durationSeconds,
            String archivedPath, String quality, long sizeBytes,
            Integer width, Integer height, String videoCodec, String audioCodec, String description,
            Long audioBitrate) {

        ExistingVideo(String ownerUsername, String channelId, String channelName, String channelHandle,
                String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
                String videoId, String title, String publishedAt, Long durationSeconds,
                String archivedPath, String quality, long sizeBytes,
                Integer width, Integer height, String videoCodec, String audioCodec) {
            this(ownerUsername, channelId, channelName, channelHandle, channelCustomPath, channelAvatarUrl,
                    channelSubscriberCount, videoId, title, publishedAt, durationSeconds, archivedPath, quality,
                    sizeBytes, width, height, videoCodec, audioCodec, null, null);
        }

        ExistingVideo(String ownerUsername, String channelId, String channelName, String channelHandle,
                String channelCustomPath, String channelAvatarUrl, Long channelSubscriberCount,
                String videoId, String title, String publishedAt, Long durationSeconds,
                String archivedPath, String quality, long sizeBytes) {
            this(ownerUsername, channelId, channelName, channelHandle, channelCustomPath, channelAvatarUrl,
                    channelSubscriberCount, videoId, title, publishedAt, durationSeconds, archivedPath, quality,
                    sizeBytes, null, null, null, null, null, null);
        }

        ExistingVideo withArchivedPath(String path) {
            return new ExistingVideo(ownerUsername, channelId, channelName, channelHandle, channelCustomPath,
                    channelAvatarUrl, channelSubscriberCount, videoId, title, publishedAt, durationSeconds,
                    path, quality, sizeBytes, width, height, videoCodec, audioCodec, description, audioBitrate);
        }
    }

    record DownloadedFile(long sizeBytes, Integer width, Integer height, String videoCodec, String audioCodec,
            String videoFormatId, String audioFormatId, String extension, Long durationSeconds, Long audioBitrate) {
        DownloadedFile(long sizeBytes, Integer width, Integer height, String videoCodec, String audioCodec,
                String videoFormatId, String audioFormatId, String extension) {
            this(sizeBytes, width, height, videoCodec, audioCodec, videoFormatId, audioFormatId, extension, null, null);
        }
    }
}
