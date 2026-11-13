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

    @Test
    void sharedVideoHasIndependentMembershipInEachPlaylistAndUserLibrary() {
        repository.upsertPlaylist(new LibraryModels.RegisterPlaylist(
                "viewer", "first", "channel", "First", java.util.List.of("shared", "first-only")));
        repository.upsertPlaylist(new LibraryModels.RegisterPlaylist(
                "viewer", "second", "channel", "Second", java.util.List.of("shared")));
        repository.upsertPlaylist(new LibraryModels.RegisterPlaylist(
                "other", "first", "channel", "First", java.util.List.of("shared")));

        String insert = "insert into library_playlist_items(user_id, playlist_id, video_id) values (?, ?, ?) on conflict do nothing";
        verify(jdbc).update(insert, "viewer", "first", "shared");
        verify(jdbc).update(insert, "viewer", "second", "shared");
        verify(jdbc).update(insert, "other", "first", "shared");
    }

    @Test
    void deleteRemovesAllOwnPlaylistLinksAndFileOnlyAfterLastOwner() {
        String path = "channel/shared/video.mkv";
        org.mockito.Mockito.when(jdbc.query(contains("select archived_path from user_views"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq("viewer"), eq("shared"))).thenReturn(path);
        org.mockito.Mockito.when(jdbc.query(contains("select archived_path from user_views"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<String>>any(),
                eq("other"), eq("shared"))).thenReturn(path);
        org.mockito.Mockito.when(jdbc.queryForObject(contains("select count(*) from user_views where video_id = ?"),
                eq(Integer.class), eq("shared"))).thenReturn(1, 0);

        assertThat(repository.deleteLink("viewer", "shared")).isNull();

        verify(jdbc).update("delete from user_views where user_id = ? and video_id = ?", "viewer", "shared");
        verify(jdbc).update("delete from library_playlist_items where user_id = ? and video_id = ?", "viewer", "shared");
        verify(jdbc, org.mockito.Mockito.never()).update("delete from videos where video_id = ?", "shared");

        assertThat(repository.deleteLink("other", "shared")).isEqualTo(path);

        verify(jdbc).update("delete from user_views where user_id = ? and video_id = ?", "other", "shared");
        verify(jdbc).update("delete from library_playlist_items where user_id = ? and video_id = ?", "other", "shared");
        verify(jdbc).update(contains("update video_versions set superseded_at = now(), deleted_at = now()"),
                eq("shared"), eq(path));
        verify(jdbc).update("delete from videos where video_id = ?", "shared");
    }
}
