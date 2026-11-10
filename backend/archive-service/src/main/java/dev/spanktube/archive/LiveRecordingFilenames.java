package dev.spanktube.archive;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class LiveRecordingFilenames {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private LiveRecordingFilenames() {
    }

    static String of(String title, String videoId, Instant startedAt, ArchiveModels.DownloadedFile file) {
        String safeTitle = String.valueOf(title).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isBlank()) {
            safeTitle = videoId;
        }
        if (safeTitle.length() > 160) {
            safeTitle = safeTitle.substring(0, 160);
        }
        String resolution = file.videoCodec() == null ? "audio"
                : file.height() == null ? "unknown" : file.height() + "p";
        String bitrate = file.audioBitrate() == null || file.audioBitrate() <= 0 ? "unknown"
                : Math.max(1, Math.round(file.audioBitrate() / 1000.0)) + "kbps";
        return safeTitle + " [" + videoId + "] (" + TIMESTAMP.format(startedAt) + ", " + resolution + ", "
                + codec(file.videoCodec()) + ", " + bitrate + ", " + codec(file.audioCodec()) + ").mkv";
    }

    private static String codec(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
