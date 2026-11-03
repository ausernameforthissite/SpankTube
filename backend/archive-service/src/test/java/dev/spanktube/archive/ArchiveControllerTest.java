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
    private final ArchiveController controller = new ArchiveController(jobs, upstream, catalog,
            mock(ArchiveEvents.class), new ArchiveProperties("/archive", 2, 5, Duration.ofHours(1), 2000), thumbnails);

    @Test
    void savesAssignedPlaylistThumbnail() {
        when(upstream.playlistArchive("playlist")).thenReturn(
                new UpstreamClient.PlaylistArchive("Playlist", "channel", List.of("video"), "https://img/playlist.jpg"));
        when(jobs.enqueue(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ArchiveModels.EnqueueResult(mock(ArchiveModels.Job.class), true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-SpankTube-User", "viewer");
        request.addHeader("X-SpankTube-Roles", "ROLE_VIEWER");

        controller.savePlaylist("playlist", request);

        verify(thumbnails).save("playlist", "https://img/playlist.jpg");
        verify(catalog).registerPlaylist("viewer", "playlist", "Playlist", "channel", List.of("video"));
    }
}
