package dev.spanktube.archive;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class UpstreamClient {

    private final RestClient client;

    UpstreamClient(@Value("${spanktube.upstream.url:http://localhost:8081}") String upstreamUrl) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();
        var requests = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(java.time.Duration.ofSeconds(90));
        client = RestClient.builder().baseUrl(upstreamUrl).requestFactory(requests).build();
    }

    void requireNotLive(String videoId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() { });
        requireNotLive(data);
    }

    private static void requireNotLive(Map<String, Object> data) {
        if ("LIVE_STREAM".equals(string(data, "streamType")) || "AUDIO_LIVE_STREAM".equals(string(data, "streamType"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Active live streams cannot be archived or downloaded");
        }
    }

    LiveSource liveVideo(String videoId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() { });
        String streamType = string(data, "streamType");
        if (!"LIVE_STREAM".equals(streamType) && !"AUDIO_LIVE_STREAM".equals(streamType)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Recording is available only for active live streams");
        }
        String dashUrl = string(data, "liveDashUrl");
        String hlsUrl = string(data, "liveHlsUrl");
        if ((dashUrl == null || dashUrl.isBlank()) && (hlsUrl == null || hlsUrl.isBlank())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "Live stream manifest is unavailable");
        }
        String manifestUrl = (dashUrl == null || dashUrl.isBlank()) && hlsUrl != null && !hlsUrl.isBlank()
                ? "/media/v/" + videoId + "/live.m3u8" : "/media/v/" + videoId + "/manifest.mpd";
        String channelId = string(data, "channelId");
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", channelId,
                firstString(data, "channelName", "channel"), string(data, "channelHandle"),
                string(data, "channelCustomPath"), string(data, "channelAvatarUrl"),
                longValue(data, "subscriberCount"), videoId, string(data, "title"),
                string(data, "publishedAt"), null, "/archive/" + channelId + "/" + videoId + "/video.mkv",
                "LIVE_RECORDING", 0, null, null, null, null, string(data, "description"), null);
        return new LiveSource(metadata, manifestUrl);
    }

    record LiveSource(ArchiveModels.ExistingVideo metadata, String manifestUrl) {
    }

    VideoDownload video(String videoId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() { });
        requireNotLive(data);
        String channelId = string(data, "channelId");
        Long duration = longValue(data, "durationSeconds");
        List<FormatOption> formats = formats(data);
        FormatOption video = bestVideo(formats);
        FormatOption audio = bestAudio(formats);
        long estimated = size(video, duration) + size(audio, duration);
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", channelId,
                firstString(data, "channelName", "channel"),
                string(data, "channelHandle"), string(data, "channelCustomPath"),
                string(data, "channelAvatarUrl"), longValue(data, "subscriberCount"),
                videoId, string(data, "title"), string(data, "publishedAt"), duration,
                "/archive/" + channelId + "/" + videoId + "/video.mkv", "bv*+ba", 0,
                video == null ? null : video.width(), video == null ? null : video.height(),
                video == null ? null : video.codecs(), audio == null ? null : audio.codecs(),
                string(data, "description"), audio == null ? null : audio.bitrate());
        return new VideoDownload(metadata, estimated, formats, subtitles(data), chapters(data));
    }

    record VideoDownload(ArchiveModels.ExistingVideo metadata, long estimatedBytes, List<FormatOption> formats,
            List<SubtitleOption> subtitles, List<ChapterOption> chapters) {
        VideoDownload(ArchiveModels.ExistingVideo metadata, long estimatedBytes) {
            this(metadata, estimatedBytes, List.of(), List.of(), List.of());
        }

        Selection select(ArchiveModels.DownloadOptions options) {
            FormatOption video = options.videoFormatId() == null ? bestVideo(formats)
                    : find(options.videoFormatId(), true);
            FormatOption audio = options.audioFormatId() == null
                    ? options.videoOnly() ? null : bestAudio(formats) : find(options.audioFormatId(), false);
            long duration = metadata.durationSeconds() == null ? 0 : metadata.durationSeconds();
            return new Selection(video, audio, size(video, duration) + size(audio, duration));
        }

        private FormatOption find(String id, boolean video) {
            return formats.stream()
                    .filter(format -> format.trackId().equals(id) || format.formatId().equals(id))
                    .filter(format -> video ? format.hasVideo() : !format.hasVideo() && format.hasAudio())
                    .sorted(java.util.Comparator.comparingInt(UpstreamClient::audioPriority).reversed())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown format " + id));
        }
    }

    record Selection(FormatOption video, FormatOption audio, long estimatedBytes) {
    }

    record FormatOption(String formatId, String mimeType, String codecs, Integer width, Integer height,
            Double fps, Long bitrate, boolean hasVideo, boolean hasAudio, Long contentLength,
            String trackId, String language, String label, Boolean original, boolean autoGenerated) {
        FormatOption(String formatId, String mimeType, String codecs, Integer width, Integer height,
                Double fps, Long bitrate, boolean hasVideo, boolean hasAudio, Long contentLength) {
            this(formatId, mimeType, codecs, width, height, fps, bitrate, hasVideo, hasAudio, contentLength,
                    formatId, null, null, null, false);
        }
    }

    record SubtitleOption(String trackId, String language, String label, boolean autoGenerated) {
        SubtitleOption(String trackId, String language, String label) {
            this(trackId, language, label, false);
        }
    }

    record ChapterOption(String title, Long startSeconds) {
    }

    private static List<SubtitleOption> subtitles(Map<String, Object> data) {
        if (!(data.get("subtitles") instanceof List<?> source)) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<?, ?>) item)
                .map(track -> new SubtitleOption(string(track, "trackId"), string(track, "language"),
                        string(track, "label"), Boolean.TRUE.equals(track.get("autoGenerated"))))
                .filter(track -> track.trackId() != null && !track.autoGenerated())
                .toList();
    }

    private static List<ChapterOption> chapters(Map<String, Object> data) {
        if (!(data.get("chapters") instanceof List<?> source)) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<?, ?>) item)
                .map(chapter -> new ChapterOption(string(chapter, "title"), longValue(chapter, "startSeconds")))
                .filter(chapter -> chapter.startSeconds() != null)
                .toList();
    }

    private static List<FormatOption> formats(Map<String, Object> data) {
        if (!(data.get("formats") instanceof List<?> source)) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<?, ?>) item)
                .map(format -> new FormatOption(string(format, "formatId"), string(format, "mimeType"),
                        string(format, "codecs"), integer(format, "width"), integer(format, "height"),
                        decimal(format, "fps"), longValue(format, "bitrate"),
                        Boolean.TRUE.equals(format.get("hasVideo")), Boolean.TRUE.equals(format.get("hasAudio")),
                        longValue(format, "contentLength"),
                        string(format, "trackId") == null ? string(format, "formatId") : string(format, "trackId"),
                        string(format, "language"), string(format, "label"), booleanValue(format, "original"),
                        Boolean.TRUE.equals(format.get("autoGenerated"))))
                .filter(format -> format.formatId() != null)
                .toList();
    }

    private static FormatOption bestVideo(List<FormatOption> formats) {
        return formats.stream().filter(format -> format.hasVideo())
                .max(Comparator.comparingInt((FormatOption format) -> format.height() == null ? 0 : format.height())
                        .thenComparingLong(format -> format.bitrate() == null ? 0 : format.bitrate())
                        .thenComparingDouble(format -> format.fps() == null ? 0 : format.fps()))
                .orElse(null);
    }

    private static FormatOption bestAudio(List<FormatOption> formats) {
        return formats.stream().filter(format -> !format.hasVideo() && format.hasAudio())
                .max(Comparator.comparingInt(UpstreamClient::audioPriority)
                        .thenComparingLong(format -> format.bitrate() == null ? 0 : format.bitrate()))
                .orElse(null);
    }

    private static int audioPriority(FormatOption format) {
        return Boolean.TRUE.equals(format.original()) ? 2 : format.autoGenerated() ? 0 : 1;
    }

    private static Boolean booleanValue(Map<?, ?> data, String key) {
        return data.get(key) instanceof Boolean value ? value : null;
    }

    static long size(FormatOption format, long duration) {
        if (format == null) {
            return 0;
        }
        if (format.contentLength() != null && format.contentLength() > 0) {
            return format.contentLength();
        }
        return format.bitrate() == null ? 0 : Math.round(format.bitrate() * duration / 8.0);
    }

    PageItems channelLiveStreams(String channelId, String cursor) {
        String path = "/internal/v1/channels/{channelId}/streams"
                + (cursor == null || cursor.isBlank() ? "" : "?cursor={cursor}");
        Map<String, Object> data = client.get().uri(path, channelId, cursor).retrieve().body(Map.class);
        return page(data);
    }

    List<String> playlistVideos(String playlistId) {
        return playlistVideos(playlistId, null).ids();
    }

    PageItems playlistVideos(String playlistId, String cursor) {
        String path = "/internal/v1/playlists/{playlistId}/videos"
                + (cursor == null || cursor.isBlank() ? "" : "?cursor={cursor}");
        Map<String, Object> data = client.get().uri(path, playlistId, cursor).retrieve().body(Map.class);
        return page(data);
    }

    PlaylistArchive playlistArchive(String playlistId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/playlists/{playlistId}", playlistId)
                .retrieve()
                .body(Map.class);
        return new PlaylistArchive(string(data, "title"), string(data, "channelId"), ids(data),
                string(data, "thumbnailUrl"));
    }

    record PlaylistArchive(String title, String channelId, List<String> videoIds, String thumbnailUrl) {
    }

    List<String> channelVideos(String channelId) {
        return channelVideos(channelId, null).ids();
    }

    PageItems channelVideos(String channelId, String cursor) {
        String path = "/internal/v1/channels/{channelId}/videos"
                + (cursor == null || cursor.isBlank() ? "" : "?cursor={cursor}");
        Map<String, Object> data = client.get().uri(path, channelId, cursor).retrieve().body(Map.class);
        return page(data);
    }

    List<String> channelPlaylists(String channelId) {
        return channelPlaylists(channelId, null).ids();
    }

    PageItems channelPlaylists(String channelId, String cursor) {
        String path = "/api/v1/channels/{channelId}/playlists"
                + (cursor == null || cursor.isBlank() ? "" : "?cursor={cursor}");
        Map<String, Object> data = client.get().uri(path, channelId, cursor).retrieve().body(Map.class);
        return page(data);
    }

    record PageItems(List<String> ids, String nextCursor, Map<String, String> streamTypes,
            Map<String, String> publishedAts) {
        PageItems(List<String> ids, String nextCursor) {
            this(ids, nextCursor, Map.of());
        }

        PageItems(List<String> ids, String nextCursor, Map<String, String> streamTypes) {
            this(ids, nextCursor, streamTypes, Map.of());
        }

        PageItems {
            ids = List.copyOf(ids);
            streamTypes = Map.copyOf(streamTypes);
            publishedAts = Map.copyOf(publishedAts);
        }

        boolean isLive(String videoId) {
            String type = streamTypes.get(videoId);
            return "LIVE_STREAM".equals(type) || "AUDIO_LIVE_STREAM".equals(type);
        }

        String publishedAt(String videoId) {
            return publishedAts.get(videoId);
        }
    }

    private static List<String> ids(Map<String, Object> data) {
        return page(data).ids();
    }

    private static PageItems page(Map<String, Object> data) {
        List<String> ids = new java.util.ArrayList<>();
        Map<String, String> streamTypes = new java.util.LinkedHashMap<>();
        Map<String, String> publishedAts = new java.util.LinkedHashMap<>();
        if (data.get("items") instanceof List<?> items) {
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> item)) {
                    continue;
                }
                Object id = item.get("videoId") == null ? item.get("id") : item.get("videoId");
                if (id instanceof String videoId) {
                    ids.add(videoId);
                    if (item.get("streamType") instanceof String type) {
                        streamTypes.put(videoId, type);
                    }
                    if (item.get("publishedAt") instanceof String publishedAt) {
                        publishedAts.put(videoId, publishedAt);
                    }
                }
            }
        }
        return new PageItems(ids, string(data, "nextCursor"), streamTypes, publishedAts);
    }

    private static String firstString(Map<String, Object> data, String first, String second) {
        String value = string(data, first);
        return value == null ? string(data, second) : value;
    }

    private static String string(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    private static Long longValue(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer integer(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double decimal(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
