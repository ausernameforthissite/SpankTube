package dev.spanktube.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewPipeDownloaderTest {

    @TempDir
    Path directory;

    private final MediaClient mediaClient = mock(MediaClient.class);
    private final SponsorBlockClient sponsorBlockClient = mock(SponsorBlockClient.class);
    private final YtDlpDownloader processes = mock(YtDlpDownloader.class);

    @Test
    void downloadsTracksThroughMediaServiceAndBuildsMkvWithoutYtDlp() throws Exception {
        when(mediaClient.downloadTrack(anyString(), anyString(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String format = invocation.getArgument(1, String.class);
                    Path output = invocation.getArgument(2, Path.class);
                    Files.writeString(output, format);
                    return (long) format.length();
                });
        when(mediaClient.downloadResource(anyString(), any())).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1, Path.class);
            Files.writeString(output, "resource");
            return 8L;
        });
        when(sponsorBlockClient.segments("dQw4w9WgXcQ"))
                .thenReturn(List.of(new SponsorBlockClient.Segment("sponsor", 60, 90)));
        UpstreamClient.VideoDownload source = source();
        TestDownloader downloader = new TestDownloader();

        ArchiveModels.DownloadedFile result = downloader.download(source,
                ArchiveModels.DownloadOptions.archiveDefaults(), directory, 1500, progress -> { });

        assertThat(result.durationSeconds()).isEqualTo(914);
        verify(mediaClient).downloadTrack(eq("dQw4w9WgXcQ"), eq("137"), any(), eq(1000L),
                eq(ArchiveModels.Stage.DOWNLOADING_VIDEO), any(), any());
        verify(mediaClient).downloadTrack(eq("dQw4w9WgXcQ"), eq("251"), any(), eq(500L),
                eq(ArchiveModels.Stage.DOWNLOADING_AUDIO), any(), any());
        verify(mediaClient).downloadResource(eq("/media/v/dQw4w9WgXcQ/sub/sub-en-0"), any());
        verify(mediaClient).downloadResource(eq("/media/v/dQw4w9WgXcQ/thumbnail"), any());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> command = ArgumentCaptor.forClass(List.class);
        verify(processes).run(command.capture(), eq(directory), eq(Duration.ofMinutes(15)), eq(1500L), any(), any());
        assertThat(command.getValue().getFirst()).isEqualTo("ffmpeg");
        assertThat(command.getValue()).contains("-c:s", "srt");
        assertThat(command.getValue().stream().map(String::valueOf)).doesNotContain("yt-dlp");
        String chapters = Files.readString(directory.resolve("chapters.ffmeta"));
        assertThat(chapters).contains("title=Start", "title=sponsor");
        var info = new ObjectMapper().readTree(directory.resolve("info.json").toFile());
        assertThat(info.path("format_id").asText()).isEqualTo("137+251");
        assertThat(info.path("extractor").asText()).isEqualTo("NewPipeExtractor");
    }

    private UpstreamClient.VideoDownload source() {
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", "channel", "Channel",
                null, null, null, null, "dQw4w9WgXcQ", "Video", null, 914L,
                "channel/dQw4w9WgXcQ/video.mkv", "bv*+ba", 0);
        List<UpstreamClient.FormatOption> formats = List.of(
                new UpstreamClient.FormatOption("137", "video/mp4", "avc1", 1920, 1080, 30.0,
                        1_780_000L, true, false, 1000L),
                new UpstreamClient.FormatOption("251", "audio/webm", "opus", null, null, null,
                        131_000L, false, true, 500L));
        return new UpstreamClient.VideoDownload(metadata, 1500, formats,
                List.of(new UpstreamClient.SubtitleOption("sub-en-0", "en", "English")),
                List.of(new UpstreamClient.ChapterOption("Start", 0L)));
    }

    private final class TestDownloader extends NewPipeDownloader {
        private TestDownloader() {
            super(new ArchiveProperties(directory.toString(), 2, 5, Duration.ofHours(1), 2000),
                    mediaClient, sponsorBlockClient, processes, new ObjectMapper(), "ffmpeg");
        }

        @Override
        public ArchiveModels.DownloadedFile inspect(Path output, ArchiveModels.DownloadOptions options) {
            return new ArchiveModels.DownloadedFile(1, 1920, 1080, "avc1", "opus", "137", "251", "mkv", 914L, 131000L);
        }
    }
}
