package dev.spanktube.archive;

import java.time.Duration;

import dev.spanktube.events.EventPublisher;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveWorkerTest {

    private final ArchiveJobService jobs = mock(ArchiveJobService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final YtDlpDownloader downloader = mock(YtDlpDownloader.class);
    private final EventPublisher publisher = mock(EventPublisher.class);

    @Test
    void rateLimitKeepsPartialFilesForRetryAndUsesLongBackoff() {
        ArchiveModels.JobItem item = item(0);
        ArchiveModels.ExistingVideo metadata = prepare(item, 0);
        when(downloader.download(any(UpstreamClient.VideoDownload.class), any(ArchiveModels.DownloadOptions.class), any(java.nio.file.Path.class),
                anyLong(), any())).thenThrow(new ArchiveDownloadException("HTTP Error 429: Too Many Requests"));

        worker().work();

        verify(downloader, never()).discard(any(ArchiveModels.ExistingVideo.class));
        verify(jobs).deferItem(eq(item), longThat(delay -> delay >= 60), contains("429"));
    }

    @Test
    void finalRateLimitFailureRemovesUnregisteredFiles() {
        ArchiveModels.JobItem item = item(2);
        ArchiveModels.ExistingVideo metadata = prepare(item, 2);
        when(downloader.download(any(UpstreamClient.VideoDownload.class), any(ArchiveModels.DownloadOptions.class), any(java.nio.file.Path.class),
                anyLong(), any())).thenThrow(new ArchiveDownloadException("HTTP Error 429: Too Many Requests"));
        when(publisher.publish(any())).thenReturn(Mono.empty());

        worker().work();

        verify(downloader).discard(metadata);
        verify(jobs).completeItem(eq(item), eq(false), contains("429"), anyLong());
    }

    @Test
    void refreshCreatesSeparateVersionAndUsesFileDuration() {
        ArchiveModels.JobItem item = item(0);
        ArchiveModels.Job job = new ArchiveModels.Job(item.jobId(), ArchiveModels.Kind.REFRESH,
                item.videoId(), "admin", "ADMIN", 0, "running", 8, 0, 0,
                null, null, null, null, null);
        when(jobs.claimNextJob()).thenReturn(job);
        when(jobs.claimNextItem(job.id())).thenReturn(item);
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "channel", "Channel",
                null, null, null, null, item.videoId(), "Video", null, 100L,
                "channel/" + item.videoId() + "/video.mkv", "bv*+ba", 0);
        when(upstream.video(item.videoId())).thenReturn(new UpstreamClient.VideoDownload(metadata, 0));
        when(downloader.download(any(UpstreamClient.VideoDownload.class), any(ArchiveModels.DownloadOptions.class),
                any(java.nio.file.Path.class), anyLong(), any())).thenReturn(new ArchiveModels.DownloadedFile(
                        100, 1920, 1080, "av1", "opus", "399", "251", "mkv", 914L, 131000L));
        when(publisher.publish(any())).thenReturn(Mono.empty());

        worker().work();

        verify(catalog, never()).existing(any(), any());
        verify(publisher).publish(org.mockito.ArgumentMatchers.argThat(event -> {
            if (!(event.payload() instanceof ArchiveModels.ExistingVideo version)) {
                return false;
            }
            return java.nio.file.Path.of(version.archivedPath()).equals(java.nio.file.Path.of(
                    "/archive/channel/" + item.videoId() + "/" + job.id() + "/video.mkv"))
                    && version.durationSeconds() == 914 && version.sizeBytes() == 100;
        }));
    }

    private ArchiveModels.ExistingVideo prepare(ArchiveModels.JobItem item, int attempts) {
        ArchiveModels.Job job = new ArchiveModels.Job(item.jobId(), ArchiveModels.Kind.VIDEO,
                item.videoId(), "admin", "ADMIN", 0, "running", 1, 0, 0,
                null, null, null, null, null);
        when(jobs.claimNextJob()).thenReturn(job);
        when(jobs.claimNextItem(job.id())).thenReturn(item);
        when(catalog.existing("admin", item.videoId())).thenReturn(java.util.Optional.empty());
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "channel", "Channel",
                null, null, null, null, item.videoId(), "Video", null, 100L,
                "channel/" + item.videoId() + "/video.mkv", "bv*+ba", 0);
        when(upstream.video(item.videoId())).thenReturn(new UpstreamClient.VideoDownload(metadata, 0));
        return metadata;
    }

    private ArchiveWorker worker() {
        return new ArchiveWorker(jobs, upstream, catalog, downloader, publisher,
                new ArchiveProperties("/archive", 2, 5, Duration.ofHours(1), 2000));
    }

    private static ArchiveModels.JobItem item(int attempts) {
        return new ArchiveModels.JobItem(1, java.util.UUID.randomUUID().toString(), "dQw4w9WgXcQ",
                "running", attempts, null, null);
    }
}
