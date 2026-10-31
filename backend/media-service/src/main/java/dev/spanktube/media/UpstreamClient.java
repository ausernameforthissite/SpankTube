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
}
