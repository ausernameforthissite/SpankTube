package dev.spanktube.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern FRAGMENT = Pattern.compile("gop-([av])-(\\d{5})\\.m4s");

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
        Path manifest = directory.resolve("ondemand.mpd");
        if (fresh(manifest, source)) {
            indexes.computeIfAbsent(directory.toString(), key -> probe(source));
            return;
        }
        Object lock = locks.computeIfAbsent(directory.toString(), key -> new Object());
        synchronized (lock) {
            if (fresh(manifest, source)) {
                indexes.computeIfAbsent(directory.toString(), key -> probe(source));
                return;
            }
            playbackLogs.log(videoId, playbackSession, "Чтение ключевых кадров архивного MKV…");
            MediaIndex index = probe(source);
            Files.createDirectories(directory);
            Path temporary = directory.resolve("ondemand.mpd.tmp");
            Files.writeString(temporary, manifest(index), StandardCharsets.UTF_8);
            Files.move(temporary, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            indexes.put(directory.toString(), index);
            playbackLogs.log(videoId, playbackSession, "DASH-манифест готов; фрагменты создаются по запросу");
        }
    }

    FileSystemResource segment(Path source, Path directory, String videoId, String filename,
            String playbackSession) throws IOException {
        prepare(source, directory, videoId, playbackSession);
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
                createSubtitle(source, subtitle, temporary);
            } else if (filename.matches("init-[av]\\.m4s")) {
                createInit(source, filename.startsWith("init-v"), temporary);
            } else {
                Matcher matcher = FRAGMENT.matcher(filename);
                if (!matcher.matches()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
                }
                int number = Integer.parseInt(matcher.group(2));
                if (number < 1 || number > index.keyframes().size()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive segment not found");
                }
                double start = index.keyframes().get(number - 1);
                double end = number < index.keyframes().size() ? index.keyframes().get(number) : index.duration();
                createFragment(source, "v".equals(matcher.group(1)), start, end, temporary);
            }
            Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new FileSystemResource(output);
        }
    }

    MediaIndex probe(Path source) {
        try {
            JsonNode metadata = objectMapper.readTree(run(List.of(ffprobe, "-v", "error",
                    "-show_entries", "format=duration,size:stream=codec_type,codec_name,profile,level,width,height,"
                            + "avg_frame_rate:stream_tags=language,title",
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
            return new MediaIndex(List.copyOf(keyframes), duration,
                    stream(metadata, "video"), stream(metadata, "audio"), metadata.path("format").path("size").asLong(0),
                    subtitleStreams(metadata));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read archived media index", error);
        }
    }

    private List<Double> probeKeyframes(Path source) {
        List<Double> keyframes = new ArrayList<>();
        for (String line : run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                "-skip_frame", "nokey", "-show_entries", "frame=best_effort_timestamp_time",
                "-of", "csv=p=0", source.toString()), PROBE_TIMEOUT).lines().toList()) {
            if (!line.isBlank()) {
                double timestamp = Double.parseDouble(line.trim());
                if (Double.isFinite(timestamp) && timestamp >= 0
                        && (keyframes.isEmpty() || timestamp > keyframes.getLast())) {
                    keyframes.add(timestamp);
                }
            }
        }
        return keyframes;
    }

    private static StreamMetadata stream(JsonNode metadata, String type) {
        for (JsonNode stream : metadata.path("streams")) {
            if (type.equals(stream.path("codec_type").asText())) {
                return new StreamMetadata(stream.path("codec_name").asText("unknown"),
                        stream.path("profile").asText(""), stream.path("level").asInt(-99),
                        stream.path("width").asInt(0), stream.path("height").asInt(0),
                        stream.path("avg_frame_rate").asText(""), stream.path("tags").path("language").asText(null),
                        stream.path("tags").path("title").asText(null));
            }
        }
        return null;
    }

    private static List<SubtitleMetadata> subtitleStreams(JsonNode metadata) {
        List<SubtitleMetadata> result = new ArrayList<>();
        for (JsonNode stream : metadata.path("streams")) {
            if ("subtitle".equals(stream.path("codec_type").asText())) {
                String language = stream.path("tags").path("language").asText("und");
                result.add(new SubtitleMetadata(language,
                        stream.path("tags").path("title").asText(language)));
            }
        }
        return List.copyOf(result);
    }

    void createSubtitle(Path source, int track, Path output) {
        run(new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                "-map", "0:s:" + track, "-f", "webvtt", output.toString())), SEGMENT_TIMEOUT);
    }

    void createInit(Path source, boolean video, Path output) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", source.toString(),
                "-map", video ? "0:v:0" : "0:a:0", "-c", "copy",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof", "-t", "0",
                "-video_track_timescale", "1000", "-f", "mp4", output.toString()));
        run(command, SEGMENT_TIMEOUT);
    }

    void createFragment(Path source, boolean video, double start, double end, Path output) {
        run(fragmentCommand(source, video, start, end, output), SEGMENT_TIMEOUT);
    }

    List<String> fragmentCommand(Path source, boolean video, double start, double end, Path output) {
        if (end <= start) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid keyframe interval");
        }
        String offset = Long.toString(Math.round(start * 1000));
        return new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y",
                "-ss", seconds(start), "-i", source.toString(),
                "-map", video ? "0:v:0" : "0:a:0", "-c", "copy", "-t", seconds(end - start),
                "-bsf", "setts=pts=PTS+" + offset + ":dts=DTS+" + offset,
                "-avoid_negative_ts", "disabled",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof+frag_discont",
                "-video_track_timescale", "1000", "-f", "mp4", output.toString()));
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
        StringBuilder result = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1.5S\" "
                + "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" mediaPresentationDuration=\"");
        result.append("PT").append(seconds(index.duration())).append("S\">\n<Period>\n");
        representation(result, index, true);
        if (index.audio() != null) {
            representation(result, index, false);
        }
        for (int position = 0; position < index.subtitles().size(); position++) {
            SubtitleMetadata subtitle = index.subtitles().get(position);
            result.append("<AdaptationSet contentType=\"text\" id=\"").append(position + 2)
                    .append("\" mimeType=\"text/vtt\" lang=\"").append(escape(subtitle.language()))
                    .append("\" label=\"").append(escape(subtitle.title())).append("\">\n")
                    .append("<Representation id=\"sub-").append(position).append("\" bandwidth=\"1\">")
                    .append("<BaseURL>subtitle-").append(position).append(".vtt</BaseURL>")
                    .append("</Representation></AdaptationSet>\n");
        }
        return result.append("</Period>\n</MPD>\n").toString();
    }

    private static void representation(StringBuilder result, MediaIndex index, boolean video) {
        StreamMetadata stream = video ? index.video() : index.audio();
        result.append("<AdaptationSet contentType=\"").append(video ? "video" : "audio")
                .append("\" id=\"").append(video ? 0 : 1).append("\" mimeType=\"")
                .append(video ? "video/mp4" : "audio/mp4").append("\" segmentAlignment=\"true\"");
        if (!video) {
            if (stream.language() != null) {
                result.append(" lang=\"").append(escape(stream.language())).append('"');
            }
            result.append(" label=\"").append(escape(stream.title() != null ? stream.title() : stream.language() != null ? stream.language() : "Audio"))
                    .append("\">\n<Role value=\"main\"/>\n");
        } else {
            result.append(">\n");
        }
        result.append("<Representation id=\"").append(video ? "v" : "a")
                .append("\" codecs=\"").append(escape(video ? videoCodec(stream) : audioCodec(stream)))
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

    private static String videoCodec(StreamMetadata stream) {
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

    private static String audioCodec(StreamMetadata stream) {
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

    private static boolean fresh(Path file, Path source) throws IOException {
        return Files.isRegularFile(file)
                && Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(source)) >= 0;
    }

    record MediaIndex(List<Double> keyframes, double duration, StreamMetadata video, StreamMetadata audio, long size,
            List<SubtitleMetadata> subtitles) {
        MediaIndex(List<Double> keyframes, double duration, StreamMetadata video, StreamMetadata audio, long size) {
            this(keyframes, duration, video, audio, size, List.of());
        }

        long bandwidth() {
            return size > 0 && duration > 0 ? Math.max(1, Math.round(size * 8 / duration)) : 1;
        }
    }

    record StreamMetadata(String codec, String profile, int level, int width, int height, String frameRate,
            String language, String title) {
        StreamMetadata(String codec, String profile, int level, int width, int height, String frameRate) {
            this(codec, profile, level, width, height, frameRate, null, null);
        }
    }

    record SubtitleMetadata(String language, String title) {
    }
}
