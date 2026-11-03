package dev.spanktube.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArchivePlaybackServiceTest {

    @TempDir
    Path directory;

    @Test
    void cacheWithTemporarySegmentsIsRebuilt() throws Exception {
        Path source = Files.writeString(directory.resolve("video.mkv"), "video");
        Path output = Files.createDirectories(directory.resolve("cache")).resolve("manifest.mpd");
        Files.writeString(output, "manifest");
        FileTime timestamp = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(source, timestamp);
        Files.setLastModifiedTime(output, timestamp);
        Path temporary = Files.writeString(directory.resolve("cache/chunk.tmp"), "partial");

        assertThat(ArchivePlaybackService.cacheReady(output, source)).isFalse();
        Files.delete(temporary);
        assertThat(ArchivePlaybackService.cacheReady(output, source)).isTrue();
        Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000));
        assertThat(ArchivePlaybackService.cacheReady(output, source)).isFalse();
    }

    @Test
    void servesArchivedPlaylistThumbnailOnlyWhenFileExists() throws Exception {
        Path thumbnail = Files.createDirectories(directory.resolve("playlists/playlist")).resolve("thumbnail");
        Files.writeString(thumbnail, "jpeg");
        ArchivePlaybackService service = new ArchivePlaybackService(mock(CatalogArchiveClient.class),
                mock(PlaybackLogService.class), directory.toString(), directory.resolve("cache").toString(),
                "ffmpeg", Duration.ofMinutes(1));

        var response = service.archivedPlaylistThumbnail("playlist").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getFile()).isEqualTo(thumbnail.toFile());
        assertThat(service.archivedPlaylistThumbnail("missing").block()).isNull();
    }
}
