package dev.spanktube.media;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileServiceTest {

    @TempDir
    Path directory;

    @Test
    void browsesDirectoriesAndVideosAndFindsOnlySameNameSubtitles() throws Exception {
        Path root = Files.createDirectories(directory.resolve("share"));
        Files.createDirectories(root.resolve("Show/Season 1"));
        Files.writeString(root.resolve("Show/Season 1/S01E01.mkv"), "video");
        Files.writeString(root.resolve("Show/Season 1/S01E01.ass"), "ass");
        Files.writeString(root.resolve("Show/Season 1/S01E01.srt"), "srt");
        Files.writeString(root.resolve("Show/Season 1/S01E01.txt"), "notes");
        Files.writeString(root.resolve("Show/Season 1/S01E02.srt"), "other");
        Files.writeString(root.resolve("Show/Season 1/S01E02.mp4"), "video");
        LocalFileService service = new LocalFileService(root.toString());

        LocalFileService.LocalDirectory show = service.browse("Show");
        LocalFileService.LocalDirectory season = service.browse("Show/Season 1");
        LocalFileService.LocalVideo video = service.video(season.entries().getFirst().id());

        assertThat(show.path()).isEqualTo("Show");
        assertThat(show.parentPath()).isEqualTo("");
        assertThat(show.entries()).extracting(LocalFileService.LocalFileEntry::kind)
                .containsExactly("DIRECTORY");
        assertThat(season.entries()).extracting(LocalFileService.LocalFileEntry::name)
                .containsExactly("S01E01.mkv", "S01E02.mp4");
        assertThat(video.subtitles()).extracting(path -> path.getFileName().toString())
                .containsExactly("S01E01.ass", "S01E01.srt");
        assertThat(video.path()).isEqualTo("Show/Season 1/S01E01.mkv");
        assertThat(service.open("Show").kind()).isEqualTo("DIRECTORY");
        assertThat(service.open("Show/Season 1/S01E01.mkv").manifestUrl())
                .isEqualTo(season.entries().getFirst().manifestUrl());
        assertThat(service.open("").path()).isEmpty();
        assertThat(season.entries().getFirst().manifestUrl())
                .isEqualTo("/media/files/" + season.entries().getFirst().id() + "/manifest.mpd");
    }

    @Test
    void rejectsTraversalAndSymlinksOutsideTheShare() throws Exception {
        Path root = Files.createDirectories(directory.resolve("share"));
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Files.writeString(outside.resolve("secret.mkv"), "outside");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        LocalFileService service = new LocalFileService(root.toString());

        assertThatThrownBy(() -> service.browse("../"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.browse("").entries()).isEmpty();
        assertThatThrownBy(() -> service.video(LocalFileService.encode("escape/secret.mkv")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
