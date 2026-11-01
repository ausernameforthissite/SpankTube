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
        when(downloader.download(eq(metadata), any(ArchiveModels.DownloadOptions.class), any(java.nio.file.Path.class),
                anyLong(), any())).thenThrow(new ArchiveDownloadException("HTTP Error 429: Too Many Requests"));

        worker().work();

        verify(downloader, never()).discard(any(ArchiveModels.ExistingVideo.class));
        verify(jobs).deferItem(eq(item), longThat(delay -> delay >= 60), contains("429"));
    }

    @Test
    void finalRateLimitFailureRemovesUnregisteredFiles() {
        ArchiveModels.JobItem item = item(2);
        ArchiveModels.ExistingVideo metadata = prepare(item, 2);
        when(downloader.download(eq(metadata), any(ArchiveModels.DownloadOptions.class), any(java.nio.file.Path.class),
                anyLong(), any())).thenThrow(new ArchiveDownloadException("HTTP Error 429: Too Many Requests"));
        when(publisher.publish(any())).thenReturn(Mono.empty());

        worker().work();

        verify(downloader).discard(metadata);
        verify(jobs).completeItem(eq(item), eq(false), contains("429"), anyLong());
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
