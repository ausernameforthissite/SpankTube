package dev.spanktube.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import dev.spanktube.egress.EgressProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class YtDlpDownloaderTest {

    @TempDir
    Path archiveRoot;

    @Test
    void failedRefreshDiscardsOnlyItsOwnVersion() throws Exception {
        Path original = Files.createDirectories(archiveRoot.resolve("channel/video")).resolve("video.mkv");
        Files.writeString(original, "original");
        Path version = Files.createDirectories(original.getParent().resolve("job")).resolve("video.mkv");
        Files.writeString(version, "version");
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "channel", "Channel",
                null, null, null, null, "video", "Video", null, 100L,
                version.toString(), "bv*+ba", 0);
        YtDlpDownloader downloader = new YtDlpDownloader(
                new ArchiveProperties(archiveRoot.toString(), 2, 5, Duration.ofHours(1), 2000),
                new EgressProperties(EgressProperties.Mode.DIRECT, "", 1080, "", "", true, null),
                "yt-dlp", "ffmpeg", "ffprobe", new ObjectMapper());

        downloader.discard(metadata);

        assertThat(version.getParent()).doesNotExist();
        assertThat(original).exists();
    }
}
