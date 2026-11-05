package dev.spanktube.upstream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class KidsVideoParser {

    private final ObjectMapper objectMapper;

    KidsVideoParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Optional<VideoDetails> parse(String videoId, String json) {
        JsonNode response = objectMapper.readTree(json);
        if (!isMadeForKids(response)) {
            return Optional.empty();
        }
        JsonNode playability = response.path("playabilityStatus");
        String status = playability.path("status").asText("");
        if (!"OK".equalsIgnoreCase(status)) {
            throw UpstreamException.failed("Android Reel client: " + status + ": "
                    + playability.path("reason").asText("Unknown playability error"));
        }
        JsonNode details = response.path("videoDetails");
        JsonNode microformat = response.path("microformat").path("playerMicroformatRenderer");
        List<StreamFormat> formats = formats(response.path("streamingData"));
        if (formats.isEmpty()) {
            throw UpstreamException.failed("Android Reel client returned no directly playable DASH formats");
        }
        String description = details.path("shortDescription").asText(null);
        String thumbnail = bestImage(details.path("thumbnail").path("thumbnails"));
        String publishedAt = microformat.path("publishDate").asText(microformat.path("uploadDate").asText(null));
        return Optional.of(new VideoDetails(videoId, details.path("title").asText(videoId), description,
                details.path("channelId").asText(null), details.path("author").asText(null), null, null,
                details.path("channelThumbnail").asText(null), null,
                positive(details.get("lengthSeconds")), positive(details.get("viewCount")), null, publishedAt, thumbnail,
                formats, subtitles(response.path("captions")),
                NewPipeUpstreamProvider.chaptersFromDescription(description), true));
    }

    static boolean isMadeForKids(JsonNode response) {
        String mode = response.path("playabilityStatus").path("miniplayer")
                .path("miniplayerRenderer").path("playbackMode").asText("");
        return "PLAYBACK_MODE_PAUSED_ONLY".equals(mode);
    }

    private static List<StreamFormat> formats(JsonNode streamingData) {
        List<StreamFormat> result = new ArrayList<>();
        readFormats(streamingData.path("formats"), result);
        readFormats(streamingData.path("adaptiveFormats"), result);
        return List.copyOf(result);
    }

    private static void readFormats(JsonNode source, List<StreamFormat> result) {
        for (JsonNode format : source) {
            String url = format.path("url").asText(null);
            String fullMimeType = format.path("mimeType").asText("");
            boolean video = fullMimeType.startsWith("video/");
            boolean audio = fullMimeType.startsWith("audio/");
            int itag = format.path("itag").asInt(-1);
            if (url == null || !url.startsWith("https://") || url.contains("m3u8")
                    || (!video && !audio) || itag < 0) {
                continue;
            }
            boolean combined = video && isProgressive(itag);
            Long bitrate = positive(format.get("bitrate"));
            if (bitrate == null) {
                bitrate = positive(format.get("averageBitrate"));
            }
            result.add(new StreamFormat(Integer.toString(itag), url, fullMimeType.split(";", 2)[0],
                    codecs(format, itag), positiveInt(format.get("width")),
                    positiveInt(format.get("height")), decimal(format.get("fps")), bitrate,
                    positiveInt(format.get("audioSampleRate")), positive(format.get("contentLength")),
                    video, audio || combined, range(format.path("initRange")), range(format.path("indexRange"))));
        }
    }

    private static String codecs(JsonNode format, int itag) {
        String codecs = format.path("codecs").asText(null);
        if (codecs != null && !codecs.isBlank()) {
            return codecs;
        }
        return switch (itag) {
            case 18 -> "avc1.42001E,mp4a.40.2";
            case 22 -> "avc1.64001F,mp4a.40.2";
            case 140 -> "mp4a.40.2";
            case 249, 250, 251 -> "opus";
            default -> "unknown";
        };
    }

    private static boolean isProgressive(int itag) {
        try {
            return ItagItem.getItag(itag).itagType == ItagItem.ItagType.VIDEO;
        } catch (Exception ignored) {
            return itag == 18 || itag == 22;
        }
    }

    private static List<SubtitleTrack> subtitles(JsonNode captions) {
        List<SubtitleTrack> result = new ArrayList<>();
        for (JsonNode track : captions.path("playerCaptionsTracklistRenderer").path("captionTracks")) {
            String language = track.path("languageCode").asText(null);
            String url = track.path("baseUrl").asText(null);
            if (language == null || url == null || "asr".equals(track.path("kind").asText(""))
                    || track.path("vssId").asText("").startsWith("a.")) {
                continue;
            }
            String normalized = language.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
            result.add(new SubtitleTrack("sub-" + normalized + "-" + result.size(), language, label(track),
                    "vtt", url.contains("fmt=") ? url : url + (url.contains("?") ? "&" : "?") + "fmt=vtt"));
        }
        return List.copyOf(result);
    }

    private static String label(JsonNode track) {
        String simpleText = track.path("name").path("simpleText").asText(null);
        if (simpleText != null && !simpleText.isBlank()) {
            return simpleText;
        }
        JsonNode runs = track.path("name").path("runs");
        return !runs.isEmpty() ? runs.get(0).path("text").asText(track.path("languageCode").asText("unknown"))
                : track.path("languageCode").asText("unknown");
    }

    private static String bestImage(JsonNode images) {
        String result = null;
        long bestArea = -1;
        for (JsonNode image : images) {
            long area = image.path("width").asLong(0) * image.path("height").asLong(0);
            if (area > bestArea) {
                bestArea = area;
                result = image.path("url").asText(null);
            }
        }
        return result;
    }

    private static String range(JsonNode range) {
        Long start = nonNegative(range.get("start"));
        Long end = nonNegative(range.get("end"));
        return start != null && end != null && end > start ? start + "-" + end : null;
    }

    private static Long positive(JsonNode value) {
        Long number = nonNegative(value);
        return number != null && number > 0 ? number : null;
    }

    private static Long nonNegative(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        try {
            long number = value.isNumber() ? value.asLong() : Long.parseLong(value.asText());
            return number >= 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer positiveInt(JsonNode value) {
        Long number = positive(value);
        return number == null || number > Integer.MAX_VALUE ? null : number.intValue();
    }

    private static Double decimal(JsonNode value) {
        return value != null && value.isNumber() && value.asDouble() > 0 ? value.asDouble() : null;
    }
}
