package dev.spanktube.media;

import java.io.IOException;
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
    void probeIgnoresFfprobeStderrWarnings() throws Exception {
        Path ffprobe = directory.resolve("ffprobe");
        Files.writeString(ffprobe, """
                #!/bin/sh
                echo '[mpeg2video @ 0000] ignoring pic cod ext after 0' >&2
                printf '%s' '{"format":{"duration":"10.5","size":"1000"},"streams":[{"codec_type":"video","codec_name":"mpeg2video","profile":"Main","level":8,"width":720,"height":480,"pix_fmt":"yuv420p","avg_frame_rate":"25/1","tags":{"language":"jpn"}},{"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":2,"tags":{"language":"jpn"}}],"frames":[{"best_effort_timestamp_time":"0.000000"},{"best_effort_timestamp_time":"2.000000"}]}'
                """);
        Files.setPosixFilePermissions(ffprobe,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        OnDemandDashService service = new OnDemandDashService(
                mock(PlaybackLogService.class), new ObjectMapper(), "ffmpeg", ffprobe.toString());
        Path source = Files.writeString(directory.resolve("source.mkv"), "source");

        OnDemandDashService.MediaIndex index = service.probe(source);

        assertThat(index.keyframes()).containsExactly(0.0, 2.0);
        assertThat(index.duration()).isEqualTo(10.5);
        assertThat(index.video().codec()).isEqualTo("mpeg2video");
    }

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
    void readsJsonKeyframesWithoutCsvPunctuation() throws Exception {
        var metadata = new ObjectMapper().readTree("{\"frames\":["
                + "{\"best_effort_timestamp_time\":\"0.000000\"},"
                + "{\"best_effort_timestamp_time\":\"2.000000\"},"
                + "{\"best_effort_timestamp_time\":\"2.000000\"},"
                + "{\"best_effort_timestamp_time\":\"N/A\"},"
                + "{\"best_effort_timestamp_time\":\"4.000000\"}]}");

        assertThat(OnDemandDashService.keyframeTimestamps(metadata)).containsExactly(0.0, 2.0, 4.0);
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
    void convertsUnsupportedLocalAudioToAacWithoutChangingArchiveCommand() {
        TestService service = new TestService();

        List<String> command = service.fragmentCommand(directory.resolve("source.mkv"), false, 1, true,
                0.0, 2.0, directory.resolve("fragment.m4s"));

        assertThat(command).containsSequence("-map", "0:a:1")
                .containsSequence("-c:a", "aac", "-b:a", "192k");
        assertThat(command).doesNotContain("copy");
        assertThat(service.fragmentCommand(directory.resolve("source.mkv"), false, 0, false,
                0.0, 2.0, directory.resolve("fragment.m4s"))).containsSequence("-c", "copy");
    }

    @Test
    void convertsHi10VideoToEightBitH264() {
        TestService service = new TestService();

        List<String> command = service.fragmentCommand(directory.resolve("source.mkv"), true, 0, true,
                10.928, 20.938, directory.resolve("fragment.m4s"));

        assertThat(command).containsSequence("-c:v", "libx264", "-preset", "veryfast")
                .containsSequence("-pix_fmt", "yuv420p");
        assertThat(command).doesNotContain("copy");
        assertThat(service.fragmentCommand(directory.resolve("source.mkv"), true, 0, true,
                10.928, 20.938, directory.resolve("fragment.m4s"), true))
                .containsSequence("-map_chapters", "-1");
        assertThat(command).doesNotContain("-map_chapters");
    }

    @Test
    void convertsHevcMain10ToBrowserCompatibleH264() throws Exception {
        AtomicInteger videoTranscodes = new AtomicInteger();
        TestService service = new TestService() {
            @Override
            MediaIndex probe(Path source) {
                return new MediaIndex(List.of(0.0, 2.0), 4.0,
                        new StreamMetadata("hevc", "Main 10", -99, 1920, 1080, "25/1", null, null,
                                "yuv420p10le", 0, 0),
                        new StreamMetadata("ac3", "", -99, 0, 0, "", "rus", "MVO", null, 48000, 6),
                        10_000_000L);
            }

            @Override
            void createInit(Path source, boolean video, int track, boolean transcode, Path output,
                    boolean browserCompatibility) {
                if (video && transcode && browserCompatibility) {
                    videoTranscodes.incrementAndGet();
                }
                try {
                    Files.writeString(output, "init");
                } catch (IOException error) {
                    throw new AssertionError(error);
                }
            }
        };
        Path source = Files.writeString(directory.resolve("hdr.mkv"), "source");
        Path cache = directory.resolve("cache");

        service.prepare(source, cache, "local", "test", List.of(), true);
        String manifest = Files.readString(cache.resolve("ondemand.mpd"));
        service.segment(source, cache, "local", "init-v.m4s", null, List.of(), true);

        assertThat(manifest).contains("avc1.640033", "mp4a.40.2").doesNotContain("hev1");
        assertThat(videoTranscodes).hasValue(1);
    }

    @Test
    void seeksLocalMp4WithoutRepeatedPreroll() {
        TestService service = new TestService();
        Path source = directory.resolve("local.mp4");
        Path output = directory.resolve("gop.m4s");

        List<List<String>> video = service.mp4VideoCommands(source, 0, 2, 4, output);
        List<String> audio = service.fragmentCommand(source, false, 0, false, 2, 4, output,
                true, true);

        assertThat(video).hasSize(2);
        assertThat(video.get(0)).containsSequence("-ss", "2.000", "-i", source.toString())
                .doesNotContain("-movflags", "-bsf");
        assertThat(video.get(1)).containsSequence("-ss", "0.000", "-i",
                output.resolveSibling("gop.m4s.seek.mp4").toString());
        assertThat(audio.indexOf("-ss")).isGreaterThan(audio.indexOf("-i"));
        assertThat(audio).containsSequence("-ss", "2.000");
    }

    @Test
    void usesTwoPassSeekForLocalMkvTranscode() {
        TestService service = new TestService();
        Path source = directory.resolve("source.mkv");
        Path output = directory.resolve("gop.m4s");

        List<List<String>> commands = service.localVideoCommands(source, 0, true, 2, 4, output);

        assertThat(commands).hasSize(2);
        assertThat(commands.get(0)).containsSequence("-ss", "2.000", "-i", source.toString())
                .doesNotContain("-movflags", "-bsf", "libx264");
        assertThat(commands.get(1)).containsSequence("-ss", "0.000", "-i",
                output.resolveSibling("gop.m4s.seek.mp4").toString())
                .containsSequence("-c:v", "libx264", "-preset", "veryfast");
    }

    @Test
    void normalizesFragmentDecodeTimeInItsActualTimescale() throws Exception {
        Path fragment = directory.resolve("fragment.m4s");
        writeFragment(fragment, List.of(-23L), List.of());

        OnDemandDashService.normalizeTfdt(fragment, 1.001, 48_000);

        byte[] bytes = Files.readAllBytes(fragment);
        int type = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("tfdt");
        assertThat(type).isEqualTo(44);
        assertThat(java.nio.ByteBuffer.wrap(bytes, 52, 8).getLong()).isEqualTo(48_048);
    }

    @Test
    void normalizesEveryFragmentWithTheSameDelta() throws Exception {
        Path fragment = directory.resolve("multiple.m4s");
        writeFragment(fragment, List.of(455_789L, 458_792L), List.of());

        OnDemandDashService.normalizeTfdt(fragment, 10.928, 1000);

        byte[] bytes = Files.readAllBytes(fragment);
        assertThat(java.nio.ByteBuffer.wrap(bytes, 52, 8).getLong()).isEqualTo(10_928);
        assertThat(java.nio.ByteBuffer.wrap(bytes, 72, 8).getLong()).isEqualTo(13_931);
    }

    @Test
    void ignoresTfdtMarkerInsideMediaPayload() throws Exception {
        Path fragment = directory.resolve("payload.m4s");
        writeFragment(fragment, List.of(455_789L), List.of("xxtfdt-tfdt".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));

        OnDemandDashService.normalizeTfdt(fragment, 10.928, 1000);

        byte[] bytes = Files.readAllBytes(fragment);
        assertThat(java.nio.ByteBuffer.wrap(bytes, 52, 8).getLong()).isEqualTo(10_928);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("xxtfdt-tfdt");
    }

    private static void writeFragment(Path target, List<Long> decodeTimes, List<byte[]> payloads) throws Exception {
        var output = new java.io.DataOutputStream(Files.newOutputStream(target));
        int moofSize = 8 + (8 + 4) + trafSize(decodeTimes);
        output.writeInt(moofSize);
        output.writeBytes("moof");
        output.writeInt(12);
        output.writeBytes("mfhd");
        output.writeInt(0);
        output.writeInt(trafSize(decodeTimes));
        output.writeBytes("traf");
        output.writeInt(8 + 4);
        output.writeBytes("tfhd");
        output.writeInt(0);
        for (long value : decodeTimes) {
            output.writeInt(20);
            output.writeBytes("tfdt");
            output.writeByte(1);
            output.writeByte(0);
            output.writeShort(0);
            output.writeLong(value);
        }
        for (byte[] payload : payloads) {
            output.writeInt(8 + payload.length);
            output.writeBytes("mdat");
            output.write(payload);
        }
        output.close();
    }

    private static int trafSize(List<Long> decodeTimes) {
        return 8 + (8 + 4) + decodeTimes.size() * 20;
    }

    @Test
    void stripsTrailingMediaFromLocalInit() throws Exception {
        Path media = directory.resolve("frame.m4s");
        Path init = directory.resolve("init.m4s");
        var output = new java.io.DataOutputStream(Files.newOutputStream(media));
        for (String[] box : List.of(new String[]{"ftyp", "isom"}, new String[]{"moov", "mvhd"},
                new String[]{"moof", "mfhd"}, new String[]{"mdat", "timecode"})) {
            byte[] payload = box[1].getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            output.writeInt(8 + payload.length);
            output.writeBytes(box[0]);
            output.write(payload);
        }
        output.close();

        OnDemandDashService.extractInit(media, init);

        String result = Files.readString(init, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(result).contains("ftyp", "moov").doesNotContain("moof", "mdat", "timecode");
        assertThat(init).hasSize(24);
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

    @Test
    void exposesMultipleAudioTracksAndMatchingExternalSubtitles() throws Exception {
        List<Integer> initialized = new ArrayList<>();
        List<Integer> fragmented = new ArrayList<>();
        List<Path> converted = new ArrayList<>();
        OnDemandDashService service = new OnDemandDashService(mock(PlaybackLogService.class), new ObjectMapper(),
                "ffmpeg", "ffprobe") {
            @Override
            MediaIndex probe(Path source) {
                StreamMetadata video = new StreamMetadata("h264", "High", 40, 640, 360, "25/1");
                StreamMetadata english = new StreamMetadata("aac", "", -99, 0, 0, "", "en", "English");
                StreamMetadata japanese = new StreamMetadata("aac", "", -99, 0, 0, "", "ja", "Japanese");
                return new MediaIndex(List.of(0.0, 2.0), 3.0, video, english, 1000,
                        List.of(new SubtitleMetadata("en", "Embedded", 0, null)), List.of(japanese));
            }

            @Override
            void createInit(Path source, boolean video, int track, Path output) {
                initialized.add(track);
                write(output);
            }

            @Override
            void createFragment(Path source, boolean video, int track, double start, double end, Path output) {
                fragmented.add(track);
                write(output);
            }

            @Override
            void createExternalSubtitle(Path source, Path output) {
                converted.add(source);
                write(output);
            }

            private void write(Path output) {
                try {
                    Files.writeString(output, "media");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        };
        Path source = Files.writeString(directory.resolve("S01E01.mkv"), "source");
        Path sidecar = Files.writeString(directory.resolve("S01E01.ass"), "ass");
        Path cache = directory.resolve("cache");

        service.prepare(source, cache, "local", null, List.of(sidecar));
        String manifest = Files.readString(cache.resolve("ondemand.mpd"));

        assertThat(manifest).contains("id=\"a0\"", "id=\"a1\"", "English", "Japanese",
                "init-$RepresentationID$.m4s", "gop-$RepresentationID$-$Number%05d$.m4s",
                "S01E01.ass", "subtitle-1.vtt");
        assertThat(service.segment(source, cache, "local", "init-a1.m4s", null, List.of(sidecar)).exists()).isTrue();
        assertThat(service.segment(source, cache, "local", "gop-a1-00001.m4s", null, List.of(sidecar)).exists()).isTrue();
        assertThat(service.segment(source, cache, "local", "subtitle-1.vtt", null, List.of(sidecar)).exists()).isTrue();
        assertThat(initialized).containsExactly(1);
        assertThat(fragmented).containsExactly(1);
        assertThat(converted).containsExactly(sidecar);
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
