package dev.spanktube.catalog;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CatalogRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CatalogRepository repository = new CatalogRepository(jdbc);

    @Test
    void updateProgressUpsertsUserPosition() {
        repository.updateProgress("viewer", "video", 42, 25);

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into user_views"), parameters.capture());
        assertThat(parameters.getValue()).contains("viewer", "video", 42L, 25.0);
        verify(jdbc).update(contains("on conflict(user_id, video_id) do update"), any(Object[].class));
    }

    @Test
    void progressUsesPerUserTimeline() {
        repository.progress("viewer");

        verify(jdbc).query(contains("last_position_sec"), any(org.springframework.jdbc.core.RowMapper.class), eq("viewer"));
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void emptyArchivePlaylistDoesNotContainNullVideoIds() throws Exception {
        ArgumentCaptor<org.springframework.jdbc.core.RowMapper<LibraryModels.LibraryPlaylist>> mapper =
                ArgumentCaptor.forClass(org.springframework.jdbc.core.RowMapper.class);
        org.mockito.Mockito.when(jdbc.query(contains("filter (where lpi.video_id is not null)"),
                any(Object[].class), mapper.capture())).thenAnswer(invocation -> {
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    java.sql.Array ids = mock(java.sql.Array.class);
                    org.mockito.Mockito.when(rs.getArray("video_ids")).thenReturn(ids);
                    org.mockito.Mockito.when(rs.getString("playlist_id")).thenReturn("playlist");
                    org.mockito.Mockito.when(rs.getString("title")).thenReturn("Empty");
                    org.mockito.Mockito.when(rs.getString("thumbnail_url"))
                            .thenReturn("/media/playlist/playlist/archive-thumbnail");
                    org.mockito.Mockito.when(ids.getArray()).thenReturn(new String[] { null });
                    return java.util.List.of(mapper.getValue().mapRow(rs, 0));
                });

        var playlists = repository.playlists("viewer", "channel");

        assertThat(playlists).singleElement().satisfies(playlist -> {
            assertThat(playlist.title()).isEqualTo("Empty");
            assertThat(playlist.videoIds()).isEmpty();
            assertThat(playlist.thumbnailUrl()).isEqualTo("/media/playlist/playlist/archive-thumbnail");
        });
    }
}
