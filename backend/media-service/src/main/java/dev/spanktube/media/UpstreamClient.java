package dev.spanktube.media;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
class UpstreamClient {

    private final WebClient webClient;

    UpstreamClient(MediaProperties properties) {
        webClient = WebClient.builder().baseUrl(properties.upstreamUrl().toString()).build();
    }

    Mono<UpstreamVideo> video(String videoId) {
        return webClient.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .bodyToMono(UpstreamVideo.class);
    }

    Mono<String> thumbnail(String videoId) {
        return webClient.get()
                .uri("/internal/v1/videos/{videoId}", videoId)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .map(data -> stringValue(data.get("thumbnailUrl")));
    }

    Mono<String> avatar(String channelId) {
        return webClient.get()
                .uri("/internal/v1/channels/{channelId}", channelId)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .map(data -> stringValue(data.get("avatarUrl")));
    }

    Mono<String> playlistThumbnail(String playlistId) {
        return webClient.get()
                .uri("/internal/v1/playlists/{playlistId}", playlistId)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .map(data -> stringValue(data.get("thumbnailUrl")));
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
