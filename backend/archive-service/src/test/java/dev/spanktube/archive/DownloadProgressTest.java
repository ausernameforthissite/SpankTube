package dev.spanktube.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadProgressTest {

    @Test
    void parsesDownloadSpeedBytesAndStages() {
        DownloadProgress.Tracker tracker = new DownloadProgress.Tracker();
        DownloadProgress.Snapshot progress = tracker
                .accept("[download]  50.0% of   10.00MiB at    2.00MiB/s", 20 * 1024 * 1024)
                .orElseThrow();

        assertThat(progress.stage()).isEqualTo(ArchiveModels.Stage.DOWNLOADING);
        assertThat(progress.downloadedBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(progress.totalBytes()).isEqualTo(20 * 1024 * 1024);
        assertThat(progress.speedBps()).isEqualTo(2 * 1024 * 1024);
        assertThat(tracker.accept("Writing video subtitles: en", 100).orElseThrow().stage())
                .isEqualTo(ArchiveModels.Stage.SUBTITLES);
        assertThat(tracker.accept("[ModifyChapters] SponsorBlock", 100).orElseThrow().stage())
                .isEqualTo(ArchiveModels.Stage.CHAPTERS);
    }
}
