package dev.spanktube.archive;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveControllerTest {

    private final ArchiveJobService jobs = mock(ArchiveJobService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final PlaylistThumbnailService thumbnails = mock(PlaylistThumbnailService.class);
    private final ArchivePreflightService preflights = mock(ArchivePreflightService.class);
    private final ArchiveController controller = new ArchiveController(jobs, upstream, catalog,
            mock(ArchiveEvents.class), new ArchiveProperties("/archive", 2, 5, Duration.ofHours(1), 2000), thumbnails,
            preflights);

    @Test
    void savesAssignedPlaylistThumbnail() {
        when(upstream.playlistArchive("playlist")).thenReturn(
                new UpstreamClient.PlaylistArchive("Playlist", "channel", List.of("video"), "https://img/playlist.jpg"));
        when(jobs.enqueue(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ArchiveModels.EnqueueResult(mock(ArchiveModels.Job.class), true));
        ArchivePreflightService.Estimate estimate = mock(ArchivePreflightService.Estimate.class);
        when(estimate.videoIds()).thenReturn(List.of("video"));
        when(preflights.validate("estimate", ArchiveModels.Kind.PLAYLIST, "playlist", "viewer"))
                .thenReturn(estimate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-SpankTube-User", "viewer");
        request.addHeader("X-SpankTube-Roles", "ROLE_VIEWER");

        controller.savePlaylist("playlist", request, "estimate");

        verify(thumbnails).save("playlist", "https://img/playlist.jpg");
        verify(catalog).registerPlaylist("viewer", "playlist", "Playlist", "channel", List.of("video"));
    }

    @Test
    void registersAllChannelPlaylistsWithTheirOwnVideos() {
        ArchivePreflightService.Estimate estimate = mock(ArchivePreflightService.Estimate.class);
        when(estimate.videoIds()).thenReturn(List.of("v1", "v2", "v3", "v4"));
        when(preflights.validate("estimate", ArchiveModels.Kind.CHANNEL, "channel", "viewer"))
                .thenReturn(estimate);
        when(upstream.channelPlaylists("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of("p1", "p2"), null));
        when(upstream.playlistArchive("p1")).thenReturn(new UpstreamClient.PlaylistArchive("First", "channel", List.of(), null));
        when(upstream.playlistArchive("p2")).thenReturn(new UpstreamClient.PlaylistArchive("Second", "channel", List.of(), null));
        when(upstream.playlistVideos("p1", null)).thenReturn(new UpstreamClient.PageItems(List.of("v1", "v2"), "next"));
        when(upstream.playlistVideos("p1", "next")).thenReturn(new UpstreamClient.PageItems(List.of("v3"), null));
        when(upstream.playlistVideos("p2", null)).thenReturn(new UpstreamClient.PageItems(List.of("v4"), null));
        when(jobs.enqueue(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ArchiveModels.EnqueueResult(mock(ArchiveModels.Job.class), true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-SpankTube-User", "viewer");
        request.addHeader("X-SpankTube-Roles", "ROLE_VIEWER");

        controller.saveChannel("channel", request, "estimate");

        verify(catalog).registerPlaylist("viewer", "p1", "First", "channel", List.of("v1", "v2", "v3"));
        verify(catalog).registerPlaylist("viewer", "p2", "Second", "channel", List.of("v4"));
    }

    @Test
    void registersSameVideoInEachOriginalPlaylist() {
        ArchivePreflightService.Estimate estimate = mock(ArchivePreflightService.Estimate.class);
        when(estimate.videoIds()).thenReturn(List.of("shared", "first", "second"));
        when(preflights.validate("estimate", ArchiveModels.Kind.CHANNEL, "channel", "viewer"))
                .thenReturn(estimate);
        when(upstream.channelPlaylists("channel", null)).thenReturn(new UpstreamClient.PageItems(List.of("p1", "p2"), null));
        when(upstream.playlistArchive("p1")).thenReturn(new UpstreamClient.PlaylistArchive("First", "channel", List.of(), null));
        when(upstream.playlistArchive("p2")).thenReturn(new UpstreamClient.PlaylistArchive("Second", "channel", List.of(), null));
        when(upstream.playlistVideos("p1", null)).thenReturn(new UpstreamClient.PageItems(List.of("shared", "first"), null));
        when(upstream.playlistVideos("p2", null)).thenReturn(new UpstreamClient.PageItems(List.of("shared", "second"), null));
        when(jobs.enqueue(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ArchiveModels.EnqueueResult(mock(ArchiveModels.Job.class), true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-SpankTube-User", "viewer");
        request.addHeader("X-SpankTube-Roles", "ROLE_VIEWER");

        controller.saveChannel("channel", request, "estimate");

        verify(catalog).registerPlaylist("viewer", "p1", "First", "channel", List.of("shared", "first"));
        verify(catalog).registerPlaylist("viewer", "p2", "Second", "channel", List.of("shared", "second"));
    }
}
