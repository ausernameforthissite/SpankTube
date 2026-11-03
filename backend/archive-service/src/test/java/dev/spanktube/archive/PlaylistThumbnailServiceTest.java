package dev.spanktube.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaylistThumbnailServiceTest {

    @TempDir
    Path directory;

    private final MediaClient mediaClient = mock(MediaClient.class);

    @Test
    void savesAssignedThumbnail() throws Exception {
        when(mediaClient.downloadResource(anyString(), any())).thenAnswer(invocation -> {
            Files.writeString(invocation.getArgument(1, Path.class), "jpeg");
            return 4L;
        });
        PlaylistThumbnailService service = new PlaylistThumbnailService(directory.toString(), mediaClient);

        service.save("playlist", "https://img.example/one two.jpg");

        assertThat(Files.readString(directory.resolve("playlists/playlist/thumbnail"))).isEqualTo("jpeg");
        verify(mediaClient).downloadResource(
                org.mockito.ArgumentMatchers.eq("/media/image?url=https%3A%2F%2Fimg.example%2Fone+two.jpg"), any());
    }

    @Test
    void skipsMissingThumbnail() {
        PlaylistThumbnailService service = new PlaylistThumbnailService(directory.toString(), mediaClient);

        service.save("playlist", null);

        org.mockito.Mockito.verifyNoInteractions(mediaClient);
    }
}
