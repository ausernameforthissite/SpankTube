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

        String publishedAt = root.path("timestamp").isNumber()
                ? Instant.ofEpochSecond(root.path("timestamp").asLong()).toString()
                : text(root, "upload_date");
        String channelUrl = firstText(root, "channel_url", "uploader_url");
        return new VideoDetails(
                text(root, "id"),
                text(root, "title"),
                text(root, "description"),
                text(root, "channel_id"),
                firstText(root, "channel", "uploader"),
                handle(channelUrl),
                customPath(channelUrl),
                null,
                longValue(root, "subscriber_count"),
                longValue(root, "duration"),
                longValue(root, "view_count"),
                longValue(root, "like_count"),
                publishedAt,
                bestThumbnail(root),
                formats,
                List.copyOf(subtitles));
    }

    SearchResults search(String json) {
        return search(json, null);
    }

    SearchResults search(String json, String nextCursor) {
        JsonNode root = objectMapper.readTree(json);
        List<SearchItem> items = new ArrayList<>();
        for (JsonNode entry : root.path("entries")) {
            items.add(searchItem(entry));
        }
        return new SearchResults(List.copyOf(items), nextCursor);
    }

    ChannelDetails channel(String json) {
        return channel(json, null);
    }

    ChannelDetails channel(String json, String nextCursor) {
        JsonNode root = objectMapper.readTree(json);
        List<SearchItem> videos = new ArrayList<>();
        List<SearchItem> playlists = new ArrayList<>();
        for (JsonNode entry : root.path("entries")) {
            SearchItem item = searchItem(entry);
            if ("playlist".equals(item.kind())) {
                playlists.add(item);
            } else {
                videos.add(item);
            }
        }
        String channelUrl = firstText(root, "channel_url", "uploader_url");
        return new ChannelDetails(
                firstText(root, "channel_id", "uploader_id", "id"),
                firstText(root, "channel", "uploader", "title"),
                handle(channelUrl),
                customPath(channelUrl),
                bestThumbnail(root),
                longValue(root, "subscriber_count"),
                List.copyOf(videos),
                List.copyOf(playlists),
                nextCursor);
    }

    PlaylistDetails playlist(String json) {
        return playlist(json, null);
    }

    PlaylistDetails playlist(String json, String nextCursor) {
        JsonNode root = objectMapper.readTree(json);
        List<SearchItem> items = new ArrayList<>();
        String thumbnail = bestThumbnail(root);
        for (JsonNode entry : root.path("entries")) {
            SearchItem item = searchItem(entry);
            items.add(item);
            if (thumbnail == null) {
                thumbnail = bestThumbnail(entry);
            }
        }
        String channelUrl = firstText(root, "channel_url", "uploader_url");
        return new PlaylistDetails(
                firstText(root, "id", "playlist_id"),
                text(root, "title"),
                firstText(root, "channel_id", "uploader_id"),
                firstText(root, "channel", "uploader"),
                bestThumbnail(root),
                handle(channelUrl),
                customPath(channelUrl),
                longValue(root, "subscriber_count"),
                List.copyOf(items),
                nextCursor,
                thumbnail,
                longValue(root, "video_count") != null ? longValue(root, "video_count") : longValue(root, "playlist_count"),
                20);
    }

    private static SearchItem searchItem(JsonNode entry) {
        String kind = entryKind(entry);
        String id = entryId(entry, kind);
        String title = firstText(entry, "title", "channel", "uploader");
        String url = switch (kind) {
            case "channel" -> "/channel/" + id;
            case "playlist" -> "/playlist?list=" + id;
            default -> "/watch?v=" + id;
        };
        String fallback = switch (kind) {
            case "channel" -> "/media/channel/" + id + "/avatar";
            case "playlist" -> "/media/playlist/" + id + "/thumbnail";
            default -> "/media/v/" + id + "/thumbnail";
        };
        String thumbnail = "video".equals(kind) ? fallback : MediaLinks.thumbnail(bestThumbnail(entry), fallback);
        Integer videoCount = intValue(entry, "video_count");
        if (videoCount == null) {
            videoCount = intValue(entry, "playlist_count");
        }
        return new SearchItem(kind, id, title, url, thumbnail,
                firstText(entry, "channel", "uploader"), longValue(entry, "duration"), videoCount);
    }

    private static String entryKind(JsonNode entry) {
        String value = (firstText(entry, "ie_key", "extractor_key", "_type") + " ").toLowerCase(Locale.ROOT);
        String url = firstText(entry, "url", "webpage_url");
        String id = text(entry, "id");
        if (value.contains("playlist") || (url != null && url.contains("list="))
                || (id != null && id.matches("(?:PL|UU|LL|OL|RD)[A-Za-z0-9_-]+"))) {
            return "playlist";
        }
        if (value.contains("channel") || value.contains("tab")
                || (url != null && url.matches(".*(?:/channel/|/c/|/@).*"))
                || (id != null && id.startsWith("UC"))) {
            return "channel";
        }
        return "video";
    }

    private static String entryId(JsonNode entry, String kind) {
        String id = text(entry, "id");
        if (id != null) {
            return id;
        }
        String url = firstText(entry, "url", "webpage_url");
        if (url == null) {
            return "";
        }
        if (url.contains("list=")) {
            return url.substring(url.indexOf("list=") + 5).replaceAll("[^A-Za-z0-9_-].*$", "");
        }
        String[] parts = url.split("/");
        return parts[parts.length - 1];
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
            result.add(new StreamFormat(formatId, url, mimeType, codecs,
                    intValue(format, "width"), intValue(format, "height"), doubleValue(format, "fps"), bitrate,
                    intValue(format, "asr"), firstLong(format, "filesize", "filesize_approx"),
                    hasVideo, hasAudio, range(format.get("init_range")), range(format.get("index_range"))));
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

    private static String bestThumbnail(JsonNode node) {
        String single = text(node, "thumbnail");
        String best = single;
        JsonNode thumbnails = node.get("thumbnails");
        if (thumbnails != null && thumbnails.isArray() && !thumbnails.isEmpty()) {
            best = text(thumbnails.get(thumbnails.size() - 1), "url");
        }
        return best;
    }

    private static String handle(String channelUrl) {
        String handle = textFromUrl(channelUrl, "/@");
        return handle == null ? null : handle.replaceAll("[^A-Za-z0-9_.-].*$", "");
    }

    private static String customPath(String channelUrl) {
        String path = textFromUrl(channelUrl, "/c/");
        return path == null ? null : path.replaceAll("[^A-Za-z0-9_.-].*$", "");
    }

    private static String textFromUrl(String url, String marker) {
        if (url == null || !url.contains(marker)) {
            return null;
        }
        return url.substring(url.indexOf(marker) + marker.length());
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
