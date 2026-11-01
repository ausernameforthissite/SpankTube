package dev.spanktube.catalog;

import java.util.List;

final class LibraryModels {

    private LibraryModels() {
    }

    record Channel(String channelId, String title, String handle, String customPath,
            String url, String avatarUrl, Long subscriberCount) {
    }

    record Video(String videoId, String title, String publishedAt, Long durationSeconds,
            String archivedPath, String quality, long sizeBytes, String lastAccessedAt,
            Integer width, Integer height, String videoCodec, String audioCodec, Long audioBitrate) {
    }

    record LibraryItem(String videoId, String title, long durationSeconds,
            long sizeBytes, double sizeMB, String downloadUrl, String lastAccessedAt,
            String thumbnailUrl, Integer width, Integer height, String videoCodec, String audioCodec,
            Long audioBitrate, String filename) {
    }

    record FileDownload(java.nio.file.Path path, String filename) {
    }

    record ArchiveAvailability(boolean archived, String downloadUrl, String filename, ArchiveVersion version) {
    }

    record ArchiveVersion(String versionId, String videoId, String title, String description, String publishedAt,
            Long durationSeconds, long sizeBytes, Integer width, Integer height,
            String videoCodec, String audioCodec, Long audioBitrate, String channelId, String channelName,
            String channelUrl, String channelAvatarUrl, String downloadUrl, String filename, boolean current) {
    }

    record LibraryChannel(Channel channel, List<LibraryItem> videos, long totalBytes) {
    }

    record LibraryResponse(List<LibraryChannel> channels, long usedBytes,
            long quotaBytes, int lruThresholdPercent) {
    }

    record UsageResponse(long usedBytes) {
    }

    record ProgressEntry(String videoId, long positionSeconds, double watchPercent, String lastAccessedAt) {
    }

    record QuotaResponse(String username, String role, Long quotaBytes,
            long effectiveQuotaBytes, int lruThresholdPercent) {
    }

    record PrepareRequest(String username, long requiredBytes, String videoId) {
    }

    record StorageUser(String username, String role, long usedBytes,
            long effectiveQuotaBytes, int lruThresholdPercent) {
    }

    record StorageResponse(long nfsTotalBytes, long nfsFreeBytes, List<StorageUser> users) {
    }
}
