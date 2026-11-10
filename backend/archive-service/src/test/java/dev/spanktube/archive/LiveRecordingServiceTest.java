package dev.spanktube.archive;

import java.time.Duration;
import java.util.concurrent.Executor;

import dev.spanktube.events.EventPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveRecordingServiceTest {

    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final LiveRecordingRepository repository = mock(LiveRecordingRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final ArchiveDownloader downloader = mock(ArchiveDownloader.class);
    private final MediaClient media = mock(MediaClient.class);
    private final Executor executor = mock(Executor.class);
    private final LiveRecordingService service = new LiveRecordingService(upstream, repository, catalog, publisher,
            new ArchiveProperties("/archive", 2, 5, Duration.ofHours(1), 2000), downloader, media,
            new tools.jackson.databind.ObjectMapper(), executor, "http://media", "ffmpeg");

    @Test
    void schedulesOnlyANewRecording() {
        UpstreamClient.LiveSource source = source();
        when(upstream.liveVideo("live")).thenReturn(source);
        LiveRecording recording = recording("RECORDING");
        when(repository.start(any(), eq("viewer"), eq("live"), any(), eq(source.manifestUrl()), any()))
                .thenReturn(new LiveRecordingRepository.StartResult(recording, true),
                        new LiveRecordingRepository.StartResult(recording, false));

        service.start("viewer", "live");
        service.start("viewer", "live");

        verify(executor).execute(any());
    }

    @Test
    void rejectsStopWithoutActiveRecording() {
        when(repository.stop("viewer", "live")).thenReturn(null);

        assertThatThrownBy(() -> service.stop("viewer", "live"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    @Test
    void returnsStoppingStatus() {
        LiveRecording stopping = recording("STOPPING");
        when(repository.stop("viewer", "live")).thenReturn(stopping);
        when(repository.get(java.util.UUID.fromString(stopping.id()))).thenReturn(stopping);

        LiveRecording result = service.stop("viewer", "live");

        assertThat(result.status()).isEqualTo("STOPPING");
        assertThat(result.downloadUrl()).isNull();
    }

    @Test
    void refreshesHeartbeatWhileWatchPageIsOpen() {
        when(repository.latest("viewer", "live")).thenReturn(recording("RECORDING"));

        service.status("viewer", "live");

        verify(repository).touchActive("viewer", "live");
    }

    @Test
    void stopsRecordingAfterWatchPageHeartbeatExpires() {
        java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(repository.activeFiles()).thenReturn(java.util.List.of(new LiveRecordingRepository.ActiveFile(
                id, "viewer", "live", java.nio.file.Path.of("/archive/live.mkv"),
                java.time.Instant.now().minusSeconds(60))));

        service.progressAndQuota();

        verify(repository).markStopping(id);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .progress(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsInvalidRecordingQuality() {
        assertThatThrownBy(() -> service.start("viewer", "live", new LiveRecordingRequest(0, null)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    private static UpstreamClient.LiveSource source() {
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "UC", "Channel", null,
                null, null, null, "live", "Live", null, null, "/archive/UC/live/video.mkv", "LIVE_RECORDING", 0);
        return new UpstreamClient.LiveSource(metadata, "/media/v/live/live.m3u8");
    }

    private static LiveRecording recording(String status) {
        return new LiveRecording("00000000-0000-0000-0000-000000000001", "live", "viewer", status, "Live",
                "2026-11-09T21:30:00Z", null, 0, null, null, null, null);
    }
}
