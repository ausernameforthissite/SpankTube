package dev.spanktube.archive;

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
        ArchiveModels.ExistingVideo metadata = new ArchiveModels.ExistingVideo("", channelId,
                firstString(data, "channelName", "channel"),
                string(data, "channelHandle"), string(data, "channelCustomPath"),
                string(data, "channelAvatarUrl"), longValue(data, "subscriberCount"),
                videoId, string(data, "title"), string(data, "publishedAt"), duration,
                "/archive/" + channelId + "/" + videoId + "/video.mkv", "bv*+ba", 0);
        return new VideoDownload(metadata, estimatedSize(data, duration));
    }

    record VideoDownload(ArchiveModels.ExistingVideo metadata, long estimatedBytes) {
    }

    private static long estimatedSize(Map<String, Object> data, Long duration) {
        if (!(data.get("formats") instanceof List<?> formats)) {
            return 0;
        }
        long video = bestSize(formats, true, duration == null ? 0 : duration);
        long audio = bestSize(formats, false, duration == null ? 0 : duration);
        return video > 0 && audio > 0 ? video + audio : Math.max(video, audio);
    }

    private static long bestSize(List<?> formats, boolean video, long duration) {
        long best = 0;
        for (Object item : formats) {
            if (!(item instanceof Map<?, ?> format) || !Boolean.TRUE.equals(format.get("hasVideo")) == video
                    || !Boolean.TRUE.equals(format.get("hasAudio"))) {
                continue;
            }
            long size = format.get("contentLength") instanceof Number length ? length.longValue() : 0;
            if (size <= 0 && format.get("bitrate") instanceof Number bitrate) {
                size = Math.round(bitrate.doubleValue() * duration / 8);
            }
            best = Math.max(best, size);
        }
        return best;
    }

    List<String> playlistVideos(String playlistId) {
        Map<String, Object> data = client.get()
                .uri("/internal/v1/playlists/{playlistId}/videos", playlistId)
                .retrieve()
                .body(Map.class);
        return ids(data);
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

    private static String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    private static Long longValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }
}
