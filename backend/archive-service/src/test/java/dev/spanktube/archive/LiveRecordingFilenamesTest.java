package dev.spanktube.archive;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveRecordingFilenamesTest {

    @Test
    void includesRecordingStartTimeAndMediaMetadata() {
        ArchiveModels.DownloadedFile file = new ArchiveModels.DownloadedFile(1024, 1920, 1080,
                "h264", "aac", "270", "234", "mkv", 12L, 128_000L);

        String filename = LiveRecordingFilenames.of("Live", "live", Instant.parse("2026-11-09T21:30:00Z"), file);

        assertThat(filename).contains("2026-11-09_21-30-00Z", "1080p", "h264", "128kbps", "aac")
                .endsWith(".mkv");
    }
}
