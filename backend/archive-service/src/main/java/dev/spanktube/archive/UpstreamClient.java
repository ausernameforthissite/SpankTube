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

    VideoDownload video(String videoId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() { });
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
        return new VideoDownload(metadata, estimated, formats);
    }

    record VideoDownload(ArchiveModels.ExistingVideo metadata, long estimatedBytes, List<FormatOption> formats) {
        VideoDownload(ArchiveModels.ExistingVideo metadata, long estimatedBytes) {
            this(metadata, estimatedBytes, List.of());
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
                    .filter(format -> format.formatId().equals(id))
                    .filter(format -> video ? format.hasVideo() : !format.hasVideo() && format.hasAudio())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown format " + id));
        }
    }

    record Selection(FormatOption video, FormatOption audio, long estimatedBytes) {
    }

    record FormatOption(String formatId, String mimeType, String codecs, Integer width, Integer height,
            Double fps, Long bitrate, boolean hasVideo, boolean hasAudio, Long contentLength) {
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
                        longValue(format, "contentLength")))
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
                .max(Comparator.comparingLong(format -> format.bitrate() == null ? 0 : format.bitrate()))
                .orElse(null);
    }

    private static long size(FormatOption format, long duration) {
        if (format == null) {
            return 0;
        }
        if (format.contentLength() != null && format.contentLength() > 0) {
            return format.contentLength();
        }
        return format.bitrate() == null ? 0 : Math.round(format.bitrate() * duration / 8.0);
    }

    List<String> playlistVideos(String playlistId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/playlists/{playlistId}/videos", playlistId)
                .retrieve()
                .body(Map.class);
        return ids(data);
    }

    PlaylistArchive playlistArchive(String playlistId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/playlists/{playlistId}", playlistId)
                .retrieve()
                .body(Map.class);
        return new PlaylistArchive(string(data, "title"), string(data, "channelId"), ids(data));
    }

    record PlaylistArchive(String title, String channelId, List<String> videoIds) {
    }

    List<String> channelVideos(String channelId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/channels/{channelId}/videos", channelId)
                .retrieve()
                .body(Map.class);
        return ids(data);
    }

    private static List<String> ids(Map<String, Object> data) {
        Object value = data.get("items");
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<?, ?>) item)
                .map(item -> item.get("videoId") == null ? item.get("id") : item.get("videoId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
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
