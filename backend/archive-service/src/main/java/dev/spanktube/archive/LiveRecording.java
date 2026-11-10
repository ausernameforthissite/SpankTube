package dev.spanktube.archive;

record LiveRecording(String id, String videoId, String ownerUsername, String status, String title,
        String startedAt, String finishedAt, long bytesDownloaded, Long durationSeconds,
        String fileName, String downloadUrl, String error) {
}
