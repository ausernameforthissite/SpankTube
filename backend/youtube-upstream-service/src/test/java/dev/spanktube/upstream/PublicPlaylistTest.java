package dev.spanktube.upstream;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPlaylistTest {

    @Test
    void calculatesTotalPagesFromOriginalPageSize() {
        PlaylistDetails details = new PlaylistDetails("PL1", "Playlist", "UC1", "Channel", null,
                null, null, null, List.of(), "np:next", null, 101L, 30);

        PublicPlaylist playlist = PublicPlaylist.from(details);

        assertThat(playlist.videoCount()).isEqualTo(101);
        assertThat(playlist.totalPages()).isEqualTo(4);
    }
}
