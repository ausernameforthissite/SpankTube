package dev.spanktube.archive;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveChannelStatusServiceTest {

    @Test
    void countsOnlyPublicationsAfterLastArchiveAndPlaylistsOutsideLibrary() {
        ArchiveRepository repository = mock(ArchiveRepository.class);
        when(repository.lastChannelArchive("viewer", "channel"))
                .thenReturn(Instant.parse("2026-11-01T00:00:00Z"));
        UpstreamClient upstream = mock(UpstreamClient.class);
        when(upstream.channelVideos("channel", null)).thenReturn(new UpstreamClient.PageItems(
                List.of("old", "new", "unknown"), null, java.util.Map.of(), java.util.Map.of(
                        "old", "2026-10-01T00:00:00Z", "new", "2026-11-02T00:00:00Z")));
        when(upstream.channelLiveStreams("channel", null)).thenReturn(new UpstreamClient.PageItems(
                List.of("live", "recorded"), null,
                java.util.Map.of("live", "LIVE_STREAM", "recorded", "POST_LIVE_STREAM"),
                java.util.Map.of("live", "2026-11-03T00:00:00Z", "recorded", "2026-11-03T00:00:00Z")));
        when(upstream.channelPlaylists("channel", null))
                .thenReturn(new UpstreamClient.PageItems(List.of("p-old", "p-new"), null));
        when(upstream.playlistVideos("p-old", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        when(upstream.playlistVideos("p-new", null)).thenReturn(new UpstreamClient.PageItems(List.of(), null));
        CatalogClient catalog = mock(CatalogClient.class);
        when(catalog.existing("viewer", "new")).thenReturn(Optional.empty());
        when(catalog.existing("viewer", "recorded")).thenReturn(Optional.empty());
        when(catalog.archivedPlaylistIds("viewer", "channel")).thenReturn(List.of("p-old"));
        ArchivePreflightService preflights = new ArchivePreflightService(upstream, catalog, new ArchiveSpeedService(),
                new ArchivePreflightEvents(new tools.jackson.databind.ObjectMapper()));
        ArchiveChannelStatusService service = new ArchiveChannelStatusService(repository, preflights, upstream, catalog);
        try {
            ArchiveChannelStatusService.ChannelArchiveStatus status = service.status("channel", "viewer").block();

            assertThat(status).isNotNull();
            assertThat(status.lastArchivedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
            assertThat(status.newVideoCount()).isEqualTo(2);
            assertThat(status.newPlaylistCount()).isEqualTo(1);
            verify(catalog, never()).existing("viewer", "old");
            verify(catalog, never()).existing("viewer", "unknown");
            verify(catalog, never()).existing("viewer", "live");
        } finally {
            preflights.shutdown();
        }
    }

    @Test
    void doesNotCallUpstreamWithoutPreviousArchive() {
        ArchiveRepository repository = mock(ArchiveRepository.class);
        when(repository.lastChannelArchive("viewer", "channel")).thenReturn(null);
        UpstreamClient upstream = mock(UpstreamClient.class);
        CatalogClient catalog = mock(CatalogClient.class);
        ArchivePreflightService preflights = new ArchivePreflightService(upstream, catalog, new ArchiveSpeedService(),
                new ArchivePreflightEvents(new tools.jackson.databind.ObjectMapper()));
        ArchiveChannelStatusService service = new ArchiveChannelStatusService(repository, preflights, upstream, catalog);
        try {
            ArchiveChannelStatusService.ChannelArchiveStatus status = service.status("channel", "viewer").block();

            assertThat(status).isNotNull();
            assertThat(status.lastArchivedAt()).isNull();
            assertThat(status.newVideoCount()).isZero();
            assertThat(status.newPlaylistCount()).isZero();
            org.mockito.Mockito.verifyNoInteractions(upstream);
        } finally {
            preflights.shutdown();
        }
    }
}
