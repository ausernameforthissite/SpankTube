package dev.spanktube.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveFilenamesTest {

    @Test
    void buildsReadableVideoFilename() {
        ArchiveModels.DownloadedFile file = new ArchiveModels.DownloadedFile(
                100, 1920, 1080, "h264", "opus", "137", "251", "mkv");

        assertThat(ArchiveFilenames.of("Video / title", "3TYZjVzWazE", file))
                .isEqualTo("Video _ title [3TYZjVzWazE] (1080p, h264, opus).mkv");
    }

    @Test
    void buildsAudioOnlyFilename() {
        ArchiveModels.DownloadedFile file = new ArchiveModels.DownloadedFile(
                100, null, null, null, "mp3", null, "140", "mp3");

        assertThat(ArchiveFilenames.of("Audio", "3TYZjVzWazE", file))
                .isEqualTo("Audio [3TYZjVzWazE] (audio, none, mp3).mp3");
    }
}
