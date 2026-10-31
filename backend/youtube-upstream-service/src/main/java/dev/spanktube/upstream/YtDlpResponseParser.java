package dev.spanktube.upstream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class YtDlpResponseParser {

    private final ObjectMapper objectMapper;

    YtDlpResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    VideoDetails parse(String json) {
        JsonNode root = objectMapper.readTree(json);
        List<StreamFormat> formats = parseFormats(root.path("formats"));
        if (formats.isEmpty()) {
            throw UpstreamException.failed("yt-dlp returned no DASH-compatible media formats");
        }
        List<SubtitleTrack> subtitles = new ArrayList<>();
        parseSubtitles(root.path("subtitles"), false, subtitles);
        parseSubtitles(root.path("automatic_captions"), true, subtitles);

        String videoId = text(root, "id");
        String publishedAt = root.path("timestamp").isNumber()
                ? Instant.ofEpochSecond(root.path("timestamp").asLong()).toString()
                : text(root, "upload_date");
        return new VideoDetails(
                videoId,
                text(root, "title"),
                text(root, "description"),
                text(root, "channel_id"),
                firstText(root, "channel", "uploader"),
                longValue(root, "duration"),
                longValue(root, "view_count"),
                longValue(root, "like_count"),
                publishedAt,
                formats,
                List.copyOf(subtitles));
    }

    private static List<StreamFormat> parseFormats(JsonNode source) {
        List<StreamFormat> result = new ArrayList<>();
        for (JsonNode format : source) {
            String formatId = text(format, "format_id");
            String url = text(format, "url");
            String protocol = text(format, "protocol");
            String videoCodec = codec(text(format, "vcodec"));
            String audioCodec = codec(text(format, "acodec"));
            if (formatId == null || !formatId.matches("[A-Za-z0-9._-]+")
                    || url == null || !url.startsWith("https://")
                    || (protocol != null && protocol.toLowerCase(Locale.ROOT).contains("m3u8"))
                    || (videoCodec == null && audioCodec == null)) {
                continue;
            }

            boolean hasVideo = videoCodec != null;
            boolean hasAudio = audioCodec != null;
            String codecs = hasVideo && hasAudio ? videoCodec + "," + audioCodec
                    : hasVideo ? videoCodec : audioCodec;
            Double bitrateKbps = firstDouble(format, "tbr", "abr", "vbr");
            Long bitrate = bitrateKbps == null ? null : Math.round(bitrateKbps * 1000);
            String mimeType = text(format, "mime_type");
            if (mimeType == null) {
                String extension = text(format, "ext");
                String suffix = "webm".equals(extension) ? "webm" : "mp4";
                mimeType = (hasVideo ? "video/" : "audio/") + suffix;
            }
            result.add(new StreamFormat(
                    formatId,
                    url,
                    mimeType,
                    codecs,
                    intValue(format, "width"),
                    intValue(format, "height"),
                    doubleValue(format, "fps"),
                    bitrate,
                    intValue(format, "asr"),
                    firstLong(format, "filesize", "filesize_approx"),
                    hasVideo,
                    hasAudio,
                    range(format.get("init_range")),
                    range(format.get("index_range"))));
        }
        return List.copyOf(result);
    }

    private static void parseSubtitles(JsonNode source, boolean automatic, List<SubtitleTrack> result) {
        if (!source.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> languageEntry : source.properties()) {
            JsonNode vtt = null;
            for (JsonNode candidate : languageEntry.getValue()) {
                if ("vtt".equals(text(candidate, "ext")) && text(candidate, "url") != null) {
                    vtt = candidate;
                    break;
                }
            }
            if (vtt == null) {
                continue;
            }
            String language = languageEntry.getKey();
            String normalized = language.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
            String trackId = (automatic ? "auto-" : "sub-") + normalized + "-" + result.size();
            result.add(new SubtitleTrack(trackId, language,
                    language + (automatic ? " (automatic)" : ""), "vtt", text(vtt, "url")));
        }
    }

    private static String codec(String value) {
        return value == null || value.isBlank() || "none".equals(value) ? null : value;
    }

    private static String range(JsonNode range) {
        if (range == null || !range.isObject()) {
            return null;
        }
        String start = text(range, "start");
        String end = text(range, "end");
        return start == null || end == null ? null : start + "-" + end;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Long firstLong(JsonNode node, String... names) {
        for (String name : names) {
            Long value = longValue(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer intValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Double doubleValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static Double firstDouble(JsonNode node, String... names) {
        for (String name : names) {
            Double value = doubleValue(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
