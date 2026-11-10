package dev.spanktube.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OnDemandDashServiceTest {

    @TempDir
    Path directory;

    @Test
    void createsOnlyRequestedFragmentsAndCachesThem() throws Exception {
        Path source = Files.writeString(directory.resolve("source.mkv"), "source");
        Path cache = directory.resolve("cache");
        TestService service = new TestService();

        service.prepare(source, cache, "80o75wemHb8", "test");
        String manifest = Files.readString(cache.resolve("ondemand.mpd"));

        assertThat(manifest).contains("vp09.00.10.08", "opus", "init-$RepresentationID$.m4s");
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(manifest).contains("d=\"133\"", "d=\"33\"", "d=\"1834\"", "d=\"1000\"");
        assertThat(cache.resolve("gop-v-00001.m4s")).doesNotExist();
        assertThat(service.segment(source, cache, "80o75wemHb8", "init-v.m4s", null).exists()).isTrue();
        assertThat(service.segment(source, cache, "80o75wemHb8", "gop-v-00002.m4s", null).exists()).isTrue();
        assertThat(service.segment(source, cache, "80o75wemHb8", "gop-v-00002.m4s", null).exists()).isTrue();
        assertThat(service.segment(source, cache, "80o75wemHb8", "gop-v-00003.m4s", null).exists()).isTrue();
        assertThat(service.starts).containsExactly(0.133, 0.166);
        assertThat(service.probes).hasValue(1);
    }

    @Test
    void preservesAbsoluteDecodeTimeInRequestedFragment() {
        TestService service = new TestService();

        List<String> command = service.fragmentCommand(directory.resolve("source.mkv"), false,
                3.737, 5.639, directory.resolve("fragment.m4s"));

        assertThat(command).containsSequence("-bsf", "setts=pts=PTS+3737:dts=DTS+3737");
        assertThat(command).containsSequence("-avoid_negative_ts", "disabled");
        assertThat(command).contains("frag_keyframe+empty_moov+default_base_moof+frag_discont");
        assertThat(command).doesNotContain("-start_at_zero", "-output_ts_offset");
    }

    @Test
    void exposesArchivedSubtitlesWithoutPreparingVideoFragments() throws Exception {
        TestService service = new TestService() {
            @Override
            MediaIndex probe(Path source) {
                return new MediaIndex(List.of(0.0, 3.0), 4.0,
                        new StreamMetadata("h264", "High", 40, 640, 360, "25/1"),
                        new StreamMetadata("aac", "", -99, 0, 0, ""), 1000,
                        List.of(new SubtitleMetadata("en", "English")));
            }

            @Override
            void createSubtitle(Path source, int track, Path output) {
                try {
                    Files.writeString(output, "WEBVTT\n");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        };
        Path source = Files.writeString(directory.resolve("source.mkv"), "source");
        Path cache = directory.resolve("cache");

        service.prepare(source, cache, "video", "test");
        String manifest = Files.readString(cache.resolve("ondemand.mpd"));

        assertThat(manifest).contains("text/vtt", "subtitle-0.vtt", "English");
        assertThat(cache.resolve("gop-v-00001.m4s")).doesNotExist();
        assertThat(service.segment(source, cache, "video", "subtitle-0.vtt", null).exists()).isTrue();
        assertThat(cache.resolve("gop-v-00001.m4s")).doesNotExist();
    }

    private static class TestService extends OnDemandDashService {
        private final AtomicInteger probes = new AtomicInteger();
        private final List<Double> starts = new ArrayList<>();

        private TestService() {
            super(mock(PlaybackLogService.class), new ObjectMapper(), "ffmpeg", "ffprobe");
        }

        @Override
        MediaIndex probe(Path source) {
            probes.incrementAndGet();
            return new MediaIndex(List.of(0.0, 0.133, 0.166, 2.0), 3.0,
                    new StreamMetadata("vp9", "Profile 0", -99, 3840, 2160, "30000/1001"),
                    new StreamMetadata("opus", "", -99, 0, 0, ""), 3_088_828_211L);
        }

        @Override
        void createInit(Path source, boolean video, Path output) {
            try {
                Files.writeString(output, "init");
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        @Override
        void createFragment(Path source, boolean video, double start, double end, Path output) {
            starts.add(start);
            try {
                Files.writeString(output, Double.toString(start));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }
}
