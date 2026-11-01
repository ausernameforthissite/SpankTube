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

        assertThat(progress.stage()).isEqualTo(ArchiveModels.Stage.DOWNLOADING_VIDEO);
        assertThat(progress.downloadedBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(progress.totalBytes()).isEqualTo(10 * 1024 * 1024);
        assertThat(progress.speedBps()).isEqualTo(2 * 1024 * 1024);
        assertThat(tracker.accept("Writing video subtitles: en", 100).orElseThrow().stage())
                .isEqualTo(ArchiveModels.Stage.SUBTITLES);
        assertThat(tracker.accept("[SponsorBlock] Fetching segments", 100).orElseThrow().stage())
                .isEqualTo(ArchiveModels.Stage.SPONSORBLOCK);
        assertThat(tracker.accept("[ModifyChapters] SponsorBlock", 100).orElseThrow().stage())
                .isEqualTo(ArchiveModels.Stage.CHAPTERS);
    }

    @Test
    void countsEachCompletedStageAndSeparatesTrackBytes() {
        ArchiveModels.DownloadOptions options = new ArchiveModels.DownloadOptions("137", "251", true, true);
        DownloadProgress.Tracker tracker = new DownloadProgress.Tracker(options);
        tracker.accept("[download] Destination: video.f137.mp4", 0);
        tracker.accept("[download] 100.0% of 10.00MiB at 2.00MiB/s", 0);
        tracker.accept("[download] Destination: video.f251.webm", 0);

        DownloadProgress.Snapshot audio = tracker.accept("[download] 50.0% of 2.00MiB at 1.00MiB/s", 0)
                .orElseThrow();

        assertThat(audio.stage()).isEqualTo(ArchiveModels.Stage.DOWNLOADING_AUDIO);
        assertThat(audio.completedCount()).isEqualTo(2);
        assertThat(audio.downloadedBytes()).isEqualTo(1024 * 1024);
        assertThat(audio.totalBytes()).isEqualTo(2 * 1024 * 1024);
    }
}
