package dev.spanktube.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class OnDemandDashService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(OnDemandDashService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SEGMENT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SUBTITLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern FRAGMENT = Pattern.compile("gop-([av])(\\d*)-(\\d{5})\\.m4s");
    private static final Pattern INIT = Pattern.compile("init-([av])(\\d*)\\.m4s");

    private final PlaybackLogService playbackLogs;
    private final ObjectMapper objectMapper;
    private final String ffmpeg;
    private final String ffprobe;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MediaIndex> indexes = new ConcurrentHashMap<>();

    OnDemandDashService(PlaybackLogService playbackLogs, ObjectMapper objectMapper,
            @Value("${spanktube.media.ffmpeg-executable:ffmpeg}") String ffmpeg,
            @Value("${spanktube.media.ffprobe-executable:ffprobe}") String ffprobe) {
        this.playbackLogs = playbackLogs;
        this.objectMapper = objectMapper;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    void prepare(Path source, Path directory, String videoId, String playbackSession) throws IOException {
        prepare(source, directory, videoId, playbackSession, List.of());
    }

    void prepare(Path source, Path directory, String videoId, String playbackSession,
            List<Path> externalSubtitles) throws IOException {
        prepare(source, directory, videoId, playbackSession, externalSubtitles, false);
    }

    void prepare(Path source, Path directory, String videoId, String playbackSession,
            List<Path> externalSubtitles, boolean browserCompatibility) throws IOException {
        Path manifest = directory.resolve("ondemand.mpd");
        if (fresh(manifest, source)) {
            indexes.computeIfAbsent(directory.toString(), key -> probe(source, externalSubtitles));
            return;
        }
        Object lock = locks.computeIfAbsent(directory.toString(), key -> new Object());
        synchronized (lock) {
            if (fresh(manifest, source)) {
                indexes.computeIfAbsent(directory.toString(), key -> probe(source, externalSubtitles));
                return;
            }
            playbackLogs.log(videoId, playbackSession, "Чтение ключевых кадров архивного MKV…");
            MediaIndex index = probe(source, externalSubtitles);
            Files.createDirectories(directory);
            Path temporary = directory.resolve("ondemand.mpd.tmp");
            Files.writeString(temporary, manifest(index, browserCompatibility), StandardCharsets.UTF_8);
            Files.move(temporary, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            indexes.put(directory.toString(), index);
            playbackLogs.log(videoId, playbackSession, "DASH-манифест готов; фрагменты создаются по запросу");
        }
    }

    FileSystemResource segment(Path source, Path directory, String videoId, String filename,
            String playbackSession) throws IOException {
        return segment(source, directory, videoId, filename, playbackSession, List.of());
    }

    FileSystemResource segment(Path source, Path directory, String videoId, String filename,
            String playbackSession, List<Path> externalSubtitles) throws IOException {
        return segment(source, directory, videoId, filename, playbackSession, externalSubtitles, false);
    }

    FileSystemResource segment(Path source, Path directory, String videoId, String filename,
            String playbackSession, List<Path> externalSubtitles, boolean browserCompatibility) throws IOException {
        prepare(source, directory, videoId, playbackSession, externalSubtitles, browserCompatibility);
        Path output = directory.resolve(filename).normalize();
        if (!output.startsWith(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
        }
        if (fresh(output, source)) {
            return new FileSystemResource(output);
        }
        Object lock = locks.computeIfAbsent(directory + "/" + filename, key -> new Object());
        synchronized (lock) {
            if (fresh(output, source)) {
                return new FileSystemResource(output);
            }
            MediaIndex index = indexes.get(directory.toString());
            Path temporary = directory.resolve(filename + ".tmp");
            if (filename.matches("subtitle-\\d+\\.vtt")) {
                int subtitle = Integer.parseInt(filename.substring(9, filename.length() - 4));
                if (subtitle < 0 || subtitle >= index.subtitles().size()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive subtitle not found");
                }
                SubtitleMetadata metadata = index.subtitles().get(subtitle);
                if (metadata.externalSource() != null) {
                    createExternalSubtitle(metadata.externalSource(), temporary);
                } else {
                    createSubtitle(source, embeddedSubtitleTrack(index, subtitle), temporary);
                }
            } else if (INIT.matcher(filename).matches()) {
                Matcher matcher = INIT.matcher(filename);
                matcher.matches();
                boolean video = "v".equals(matcher.group(1));
                int track = video ? 0 : audioTrack(index, matcher.group(2));
                boolean convert = browserCompatibility && (video ? requiresVideoTranscode(index.video())
                        : requiresAac(index.audioTracks().get(track)));
                createInit(source, video, track, convert, temporary, browserCompatibility);
            } else {
                Matcher matcher = FRAGMENT.matcher(filename);
                if (!matcher.matches()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
                }
                boolean video = "v".equals(matcher.group(1));
                int track = video ? 0 : audioTrack(index, matcher.group(2));
                int number = Integer.parseInt(matcher.group(3));
                if (number < 1 || number > index.keyframes().size()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
                }
                double start = index.keyframes().get(number - 1);
                double end = number < index.keyframes().size() ? index.keyframes().get(number) : index.duration();
                boolean convert = browserCompatibility && (video ? requiresVideoTranscode(index.video())
                        : requiresAac(index.audioTracks().get(track)));
                createFragment(source, video, track, convert, start, end, temporary, browserCompatibility);
                if (browserCompatibility) {
                    long timescale = video ? 1000 : index.audioTracks().get(track).sampleRate();
                    normalizeTfdt(temporary, start, timescale);
                }
            }
            Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new FileSystemResource(output);
        }
    }

    MediaIndex probe(Path source) {
        try {
            JsonNode metadata = objectMapper.readTree(run(List.of(ffprobe, "-v", "error",
                    "-show_entries", "format=duration,size:stream=codec_type,codec_name,profile,level,width,height,"
                            + "pix_fmt,sample_rate,avg_frame_rate:stream_tags=language,title",
                    "-of", "json", source.toString()), PROBE_TIMEOUT));
            List<Double> keyframes;
            try {
                keyframes = MatroskaIndexReader.keyframes(source);
            } catch (IOException error) {
                LOGGER.warn("Unable to read Matroska Cues; falling back to ffprobe: {}", error.getMessage());
                keyframes = probeKeyframes(source);
            }
            if (keyframes.isEmpty()) {
                throw new IOException("No video keyframes found");
            }
            double duration = metadata.path("format").path("duration").asDouble(0);
            if (duration <= keyframes.getLast()) {
                duration = keyframes.getLast() + 0.04;
            }
            List<StreamMetadata> audio = streams(metadata, "audio");
            return new MediaIndex(List.copyOf(keyframes), duration,
                    stream(metadata, "video"), audio.isEmpty() ? null : audio.getFirst(),
                    metadata.path("format").path("size").asLong(0), subtitleStreams(metadata),
                    audio.isEmpty() ? List.of() : List.copyOf(audio.subList(1, audio.size())));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read archived media index", error);
        }
    }

    MediaIndex probe(Path source, List<Path> externalSubtitles) {
        MediaIndex index = probe(source);
        if (externalSubtitles.isEmpty()) {
            return index;
        }
        List<SubtitleMetadata> subtitles = new ArrayList<>(index.subtitles());
        for (Path subtitle : externalSubtitles) {
            String filename = subtitle.getFileName().toString();
            subtitles.add(new SubtitleMetadata("und", filename, null, subtitle));
        }
        return new MediaIndex(index.keyframes(), index.duration(), index.video(), index.audio(), index.size(),
                List.copyOf(subtitles), index.additionalAudio());
    }

    private static int embeddedSubtitleTrack(MediaIndex index, int subtitle) {
        SubtitleMetadata metadata = index.subtitles().get(subtitle);
        if (metadata.embeddedTrack() != null) {
            return metadata.embeddedTrack();
        }
        int track = 0;
        for (int position = 0; position < subtitle; position++) {
            if (index.subtitles().get(position).externalSource() == null) {
                track++;
            }
        }
        return track;
    }

    private static int audioTrack(MediaIndex index, String value) {
        int track = value.isEmpty() ? 0 : Integer.parseInt(value);
        if (track < 0 || track >= index.audioTracks().size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive audio track not found");
        }
        return track;
    }

    private List<Double> probeKeyframes(Path source) throws IOException {
        JsonNode metadata = objectMapper.readTree(run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                "-skip_frame", "nokey", "-show_entries", "frame=best_effort_timestamp_time",
                "-of", "json", source.toString()), PROBE_TIMEOUT));
        return keyframeTimestamps(metadata);
    }

    static List<Double> keyframeTimestamps(JsonNode metadata) {
        List<Double> keyframes = new ArrayList<>();
        for (JsonNode frame : metadata.path("frames")) {
            double timestamp = frame.path("best_effort_timestamp_time").asDouble(Double.NaN);
            if (Double.isFinite(timestamp) && timestamp >= 0
                    && (keyframes.isEmpty() || timestamp > keyframes.getLast())) {
                keyframes.add(timestamp);
            }
        }
        return keyframes;
    }

    private static StreamMetadata stream(JsonNode metadata, String type) {
        List<StreamMetadata> streams = streams(metadata, type);
        return streams.isEmpty() ? null : streams.getFirst();
    }

    private static List<StreamMetadata> streams(JsonNode metadata, String type) {
        List<StreamMetadata> result = new ArrayList<>();
        for (JsonNode stream : metadata.path("streams")) {
            if (type.equals(stream.path("codec_type").asText())) {
                result.add(new StreamMetadata(stream.path("codec_name").asText("unknown"),
                        stream.path("profile").asText(""), stream.path("level").asInt(-99),
                        stream.path("width").asInt(0), stream.path("height").asInt(0),
                        stream.path("avg_frame_rate").asText(""), stream.path("tags").path("language").asText(null),
                        stream.path("tags").path("title").asText(null), stream.path("pix_fmt").asText(null),
                        stream.path("sample_rate").asInt(0), stream.path("channels").asInt(0)));
            }
        }
        return List.copyOf(result);
    }

    private static List<SubtitleMetadata> subtitleStreams(JsonNode metadata) {
        List<SubtitleMetadata> result = new ArrayList<>();
        int track = 0;
        for (JsonNode stream : metadata.path("streams")) {
            if ("subtitle".equals(stream.path("codec_type").asText())) {
                String language = stream.path("tags").path("language").asText("und");
                result.add(new SubtitleMetadata(language,
                        stream.path("tags").path("title").asText(language), track++, null));
            }
        }
        return List.copyOf(result);
    }

    void createSubtitle(Path source, int track, Path output) {
        run(new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                "-map", "0:s:" + track, "-f", "webvtt", output.toString())), SUBTITLE_TIMEOUT);
    }

    void createExternalSubtitle(Path source, Path output) {
        run(new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                "-f", "webvtt", output.toString())), SEGMENT_TIMEOUT);
    }

    void createInit(Path source, boolean video, Path output) {
        run(initCommand(source, video, 0, false, false, output), SEGMENT_TIMEOUT);
    }

    void createInit(Path source, boolean video, int track, Path output) {
        if (track == 0) {
            createInit(source, video, output);
            return;
        }
        run(initCommand(source, video, track, false, false, output), SEGMENT_TIMEOUT);
    }

    void createInit(Path source, boolean video, int track, boolean transcode, Path output) {
        createInit(source, video, track, transcode, output, false);
    }

    void createInit(Path source, boolean video, int track, boolean transcode, Path output,
            boolean browserCompatibility) {
        if (!browserCompatibility) {
            if (!transcode) {
                createInit(source, video, track, output);
            } else {
                run(initCommand(source, video, track, true, false, output), SEGMENT_TIMEOUT);
            }
            return;
        }
        Path complete = output.resolveSibling(output.getFileName().toString() + ".complete");
        Path withFrame = output.resolveSibling(output.getFileName().toString() + ".frame");
        try {
            if (transcode && video) {
                List<String> command = initCommand(source, true, 0, true, true, withFrame);
                int fragmented = command.indexOf("-movflags");
                command.remove(fragmented + 1);
                command.remove(fragmented);
                int duration = command.indexOf("-t");
                command.set(duration, "-frames:v");
                command.set(duration + 1, "1");
                run(command, SEGMENT_TIMEOUT);
                run(initCommand(withFrame, true, 0, false, true, complete), SEGMENT_TIMEOUT);
            } else {
                run(initCommand(source, video, track, transcode, true, complete), SEGMENT_TIMEOUT);
            }
            extractInit(complete, output);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create local init segment", error);
        } finally {
            try {
                Files.deleteIfExists(complete);
                Files.deleteIfExists(withFrame);
            } catch (IOException error) {
                LOGGER.warn("Unable to delete temporary local init media: {}", error.getMessage());
            }
        }
    }

    private List<String> initCommand(Path source, boolean video, int track, boolean transcode,
            boolean browserCompatibility, Path output) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                "-map", video ? "0:v:0" : "0:a:" + track));
        if (browserCompatibility) {
            command.addAll(List.of("-map_chapters", "-1"));
        }
        if (transcode && video) {
            command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"));
        } else if (transcode) {
            command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        } else {
            command.addAll(List.of("-c", "copy"));
        }
        command.addAll(List.of("-movflags", "frag_keyframe+empty_moov+default_base_moof", "-t", "0",
                "-video_track_timescale", "1000", "-f", "mp4", output.toString()));
        return command;
    }

    void createFragment(Path source, boolean video, double start, double end, Path output) {
        run(fragmentCommand(source, video, start, end, output), SEGMENT_TIMEOUT);
    }

    void createFragment(Path source, boolean video, int track, double start, double end, Path output) {
        if (track == 0) {
            createFragment(source, video, start, end, output);
            return;
        }
        run(fragmentCommand(source, video, track, false, start, end, output), SEGMENT_TIMEOUT);
    }

    void createFragment(Path source, boolean video, int track, boolean transcode,
            double start, double end, Path output) {
        createFragment(source, video, track, transcode, start, end, output, false);
    }

    void createFragment(Path source, boolean video, int track, boolean transcode,
            double start, double end, Path output, boolean browserCompatibility) {
        if (!transcode && !browserCompatibility) {
            createFragment(source, video, track, start, end, output);
            return;
        }
        boolean localMp4 = browserCompatibility
                && source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4");
        if (browserCompatibility && video) {
            createLocalVideoFragment(source, track, transcode, start, end, output);
            return;
        }
        run(fragmentCommand(source, video, track, transcode, start, end, output, browserCompatibility,
                localMp4 && !video), SEGMENT_TIMEOUT);
    }

    private void createLocalVideoFragment(Path source, int track, boolean transcode,
            double start, double end, Path output) {
        Path ordinary = output.resolveSibling(output.getFileName().toString() + ".seek.mp4");
        try {
            for (List<String> command : localVideoCommands(source, track, transcode, start, end, output)) {
                run(command, SEGMENT_TIMEOUT);
            }
        } finally {
            try {
                Files.deleteIfExists(ordinary);
            } catch (IOException error) {
                LOGGER.warn("Unable to delete temporary MP4 seek media: {}", error.getMessage());
            }
        }
    }

    List<String> fragmentCommand(Path source, boolean video, double start, double end, Path output) {
        return fragmentCommand(source, video, 0, false, start, end, output);
    }

    List<String> fragmentCommand(Path source, boolean video, int track, double start, double end, Path output) {
        return fragmentCommand(source, video, track, false, start, end, output);
    }

    List<List<String>> mp4VideoCommands(Path source, int track, double start, double end, Path output) {
        return localVideoCommands(source, track, false, start, end, output);
    }

    List<List<String>> localVideoCommands(Path source, int track, boolean transcode,
            double start, double end, Path output) {
        Path ordinary = output.resolveSibling(output.getFileName().toString() + ".seek.mp4");
        List<String> seek = fragmentCommand(source, true, track, false, start, end, ordinary, true, false);
        int bsf = seek.indexOf("-bsf");
        seek.remove(bsf + 1);
        seek.remove(bsf);
        int movflags = seek.indexOf("-movflags");
        seek.remove(movflags + 1);
        seek.remove(movflags);
        return List.of(seek, fragmentCommand(ordinary, true, track, transcode, 0, end - start, output,
                true, false));
    }

    List<String> fragmentCommand(Path source, boolean video, int track, boolean transcode,
            double start, double end, Path output) {
        return fragmentCommand(source, video, track, transcode, start, end, output, false);
    }

    List<String> fragmentCommand(Path source, boolean video, int track, boolean transcode,
            double start, double end, Path output, boolean browserCompatibility) {
        return fragmentCommand(source, video, track, transcode, start, end, output, browserCompatibility, false);
    }

    List<String> fragmentCommand(Path source, boolean video, int track, boolean transcode,
            double start, double end, Path output, boolean browserCompatibility, boolean outputSeek) {
        if (end <= start) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid keyframe interval");
        }
        String offset = Long.toString(Math.round(start * 1000));
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y"));
        if (!outputSeek) {
            command.addAll(List.of("-ss", seconds(start)));
        }
        command.add("-i");
        command.add(source.toString());
        if (outputSeek) {
            command.addAll(List.of("-ss", seconds(start)));
        }
        command.addAll(List.of("-map", video ? "0:v:0" : "0:a:" + track));
        if (browserCompatibility) {
            command.addAll(List.of("-map_chapters", "-1"));
        }
        if (transcode && video) {
            command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"));
        } else if (transcode) {
            command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        } else {
            command.addAll(List.of("-c", "copy"));
        }
        command.addAll(List.of("-t", seconds(end - start),
                "-bsf", "setts=pts=PTS+" + offset + ":dts=DTS+" + offset,
                "-avoid_negative_ts", "disabled",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof+frag_discont",
                "-video_track_timescale", "1000", "-f", "mp4", output.toString()));
        return command;
    }

    private String run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process running = process;
            var outputReader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var input = running.getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException error) {
                    return error.getMessage();
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Media process timed out");
            }
            String output = outputReader.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("Media process failed: " + output.substring(0, Math.min(4000, output.length())));
            }
            return output;
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Media process failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Media process interrupted", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String manifest(MediaIndex index) {
        return manifest(index, false);
    }

    private static String manifest(MediaIndex index, boolean browserCompatibility) {
        StringBuilder result = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1.5S\" "
                + "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" mediaPresentationDuration=\"");
        result.append("PT").append(seconds(index.duration())).append("S\">\n<Period>\n");
        representation(result, index, true, 0, browserCompatibility);
        for (int track = 0; track < index.audioTracks().size(); track++) {
            representation(result, index, false, track, browserCompatibility);
        }
        for (int position = 0; position < index.subtitles().size(); position++) {
            SubtitleMetadata subtitle = index.subtitles().get(position);
            result.append("<AdaptationSet contentType=\"text\" id=\"")
                    .append(position + 1 + index.audioTracks().size())
                    .append("\" mimeType=\"text/vtt\" lang=\"").append(escape(subtitle.language()))
                    .append("\" label=\"").append(escape(subtitle.title())).append("\">\n")
                    .append("<Representation id=\"sub-").append(position).append("\" bandwidth=\"1\">")
                    .append("<BaseURL>subtitle-").append(position).append(".vtt</BaseURL>")
                    .append("</Representation></AdaptationSet>\n");
        }
        return result.append("</Period>\n</MPD>\n").toString();
    }

    private static void representation(StringBuilder result, MediaIndex index, boolean video, int track,
            boolean browserCompatibility) {
        StreamMetadata stream = video ? index.video() : index.audioTracks().get(track);
        String representationId = video ? "v" : index.audioTracks().size() == 1 ? "a" : "a" + track;
        result.append("<AdaptationSet contentType=\"").append(video ? "video" : "audio")
                .append("\" id=\"").append(video ? 0 : track + 1).append("\" mimeType=\"")
                .append(video ? "video/mp4" : "audio/mp4").append("\" segmentAlignment=\"true\"");
        if (!video) {
            if (stream.language() != null) {
                result.append(" lang=\"").append(escape(stream.language())).append('"');
            }
            String fallback = stream.language() != null ? stream.language() : "Audio " + (track + 1);
            result.append(" label=\"").append(escape(stream.title() != null ? stream.title() : fallback))
                    .append("\">\n");
            if (track == 0) {
                result.append("<Role value=\"main\"/>\n");
            }
        } else {
            result.append(">\n");
        }
        result.append("<Representation id=\"").append(representationId)
                .append("\" codecs=\"").append(escape(video ? videoCodec(stream, browserCompatibility && requiresVideoTranscode(stream))
                        : audioCodec(stream, browserCompatibility && requiresAac(stream))))
                .append("\" bandwidth=\"").append(index.bandwidth()).append('"');
        if (video && stream.width() > 0) {
            result.append(" width=\"").append(stream.width()).append('"');
        }
        if (video && stream.height() > 0) {
            result.append(" height=\"").append(stream.height()).append('"');
        }
        if (video && stream.frameRate() != null && !stream.frameRate().isBlank()
                && !"0/0".equals(stream.frameRate())) {
            result.append(" frameRate=\"").append(escape(stream.frameRate())).append('"');
        }
        result.append(">\n<SegmentTemplate timescale=\"1000\" startNumber=\"1\"")
                .append(" initialization=\"init-$RepresentationID$.m4s\"")
                .append(" media=\"gop-$RepresentationID$-$Number%05d$.m4s\">\n<SegmentTimeline>\n");
        for (int position = 0; position < index.keyframes().size(); position++) {
            double start = index.keyframes().get(position);
            double end = position + 1 < index.keyframes().size() ? index.keyframes().get(position + 1) : index.duration();
            result.append("<S");
            if (position == 0 && start > 0) {
                result.append(" t=\"").append(Math.round(start * 1000)).append('"');
            }
            result.append(" d=\"").append(Math.max(1, Math.round((end - start) * 1000))).append("\"/>\n");
        }
        result.append("</SegmentTimeline>\n</SegmentTemplate>\n</Representation>\n</AdaptationSet>\n");
    }

    private static String videoCodec(StreamMetadata stream, boolean transcode) {
        if (transcode) {
            return "avc1.640033";
        }
        if ("vp9".equals(stream.codec())) {
            return "vp09.00.10.08";
        }
        if ("av1".equals(stream.codec())) {
            return "av01";
        }
        if ("hevc".equals(stream.codec())) {
            return "hev1";
        }
        if ("h264".equals(stream.codec()) && stream.level() > 0) {
            String profile = stream.profile().toLowerCase(Locale.ROOT);
            String prefix = profile.contains("high") ? "64" : profile.contains("main") ? "4d" : "42";
            return String.format(Locale.ROOT, "avc1.%s40%02X", prefix, stream.level());
        }
        return "avc1";
    }

    private static boolean requiresVideoTranscode(StreamMetadata stream) {
        if (!Set.of("h264", "vp9", "av1").contains(stream.codec())) {
            return true;
        }
        if (!"h264".equals(stream.codec())) {
            return false;
        }
        return stream.profile().toLowerCase(Locale.ROOT).contains("high 10")
                || stream.pixFmt() != null && !"yuv420p".equals(stream.pixFmt());
    }

    private static boolean requiresAac(StreamMetadata stream) {
        if (stream.channels() > 2) {
            return true;
        }
        return switch (stream.codec()) {
            case "aac", "opus", "vorbis" -> false;
            default -> true;
        };
    }

    private static String audioCodec(StreamMetadata stream, boolean transcode) {
        if (transcode) {
            return "mp4a.40.2";
        }
        return switch (stream.codec()) {
            case "aac" -> "mp4a.40.2";
            case "opus" -> "opus";
            case "vorbis" -> "vorbis";
            default -> stream.codec();
        };
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static void extractInit(Path source, Path output) throws IOException {
        boolean moov = false;
        try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r");
                var target = new java.io.DataOutputStream(Files.newOutputStream(output))) {
            while (!moov) {
                long boxStart = input.getFilePointer();
                int size32 = input.readInt();
                byte[] type = new byte[4];
                input.readFully(type);
                long size = Integer.toUnsignedLong(size32);
                int header = 8;
                if (size32 == 1) {
                    size = input.readLong();
                    header = 16;
                } else if (size32 == 0) {
                    size = input.length() - boxStart;
                }
                if (size < header || boxStart + size > input.length()) {
                    throw new IOException("Invalid MP4 box in local init media");
                }
                String name = new String(type, StandardCharsets.ISO_8859_1);
                if ("ftyp".equals(name) || "moov".equals(name)) {
                    target.writeInt(size32);
                    target.write(type);
                    if (header == 16) {
                        target.writeLong(size);
                    }
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = size - header;
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) {
                            throw new java.io.EOFException("Truncated MP4 init box");
                        }
                        target.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                moov = "moov".equals(name);
                input.seek(boxStart + size);
            }
        }
    }

    static void normalizeTfdt(Path fragment, double start, long timescale) throws IOException {
        if (!Double.isFinite(start) || start < 0 || timescale <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid media fragment timestamp");
        }
        long normalized = Math.round(start * timescale);
        try (RandomAccessFile output = new RandomAccessFile(fragment.toFile(), "rw")) {
            List<Long> boxes = findTfdt(output);
            long shift = readTfdt(output, boxes.getFirst()) - normalized;
            for (long type : boxes) {
                writeTfdt(output, type, readTfdt(output, type) - shift);
            }
        }
    }

    private static long readTfdt(RandomAccessFile output, long type) throws IOException {
        output.seek(type - 4);
        long size = output.readInt();
        output.seek(type + 4);
        int version = output.readUnsignedByte();
        output.seek(type + 8);
        if (version == 1 && size == 20) {
            return output.readLong();
        }
        if (version == 0 && size == 16) {
            return Integer.toUnsignedLong(output.readInt());
        }
        throw new IOException("Unsupported tfdt box version/size " + version + "/" + size);
    }

    private static void writeTfdt(RandomAccessFile output, long type, long value) throws IOException {
        output.seek(type + 4);
        int version = output.readUnsignedByte();
        output.seek(type + 8);
        if (version == 1) {
            output.writeLong(value);
        } else if (version == 0) {
            if (value < 0 || value > 0xFFFFFFFFL) {
                throw new IOException("tfdt value is outside version 0 range");
            }
            output.writeInt((int) value);
        }
    }

    private static List<Long> findTfdt(RandomAccessFile output) throws IOException {
        byte[] expected = {'t', 'f', 'd', 't'};
        byte[] buffer = new byte[64 * 1024];
        List<Long> result = new ArrayList<>();
        long base = 0;
        int matched = 0;
        int count;
        while ((count = output.read(buffer)) >= 0) {
            for (int index = 0; index < count; index++) {
                if (buffer[index] == expected[matched]) {
                    matched++;
                    if (matched == expected.length) {
                        result.add(base + index - 3);
                        matched = 0;
                    }
                } else {
                    matched = buffer[index] == expected[0] ? 1 : 0;
                }
            }
            base += count;
        }
        if (result.isEmpty()) {
            throw new IOException("Media fragment has no tfdt box");
        }
        return result;
    }

    private static boolean fresh(Path file, Path source) throws IOException {
        return Files.isRegularFile(file)
                && Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(source)) >= 0;
    }

    record MediaIndex(List<Double> keyframes, double duration, StreamMetadata video, StreamMetadata audio, long size,
            List<SubtitleMetadata> subtitles, List<StreamMetadata> additionalAudio) {
        MediaIndex(List<Double> keyframes, double duration, StreamMetadata video, StreamMetadata audio, long size,
                List<SubtitleMetadata> subtitles) {
            this(keyframes, duration, video, audio, size, subtitles, List.of());
        }

        MediaIndex(List<Double> keyframes, double duration, StreamMetadata video, StreamMetadata audio, long size) {
            this(keyframes, duration, video, audio, size, List.of());
        }

        List<StreamMetadata> audioTracks() {
            if (audio == null) {
                return List.of();
            }
            List<StreamMetadata> tracks = new ArrayList<>();
            tracks.add(audio);
            tracks.addAll(additionalAudio);
            return List.copyOf(tracks);
        }

        long bandwidth() {
            return size > 0 && duration > 0 ? Math.max(1, Math.round(size * 8 / duration)) : 1;
        }
    }

    record StreamMetadata(String codec, String profile, int level, int width, int height, String frameRate,
            String language, String title, String pixFmt, int sampleRate, int channels) {
        StreamMetadata(String codec, String profile, int level, int width, int height, String frameRate,
                String language, String title) {
            this(codec, profile, level, width, height, frameRate, language, title, null, 0, 0);
        }

        StreamMetadata(String codec, String profile, int level, int width, int height, String frameRate) {
            this(codec, profile, level, width, height, frameRate, null, null);
        }
    }

    record SubtitleMetadata(String language, String title, Integer embeddedTrack, Path externalSource) {
        SubtitleMetadata(String language, String title) {
            this(language, title, null, null);
        }
    }
}
