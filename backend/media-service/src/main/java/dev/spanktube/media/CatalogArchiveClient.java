package dev.spanktube.media;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
class CatalogArchiveClient {

    private final WebClient client;

    CatalogArchiveClient(MediaProperties properties) {
        client = WebClient.builder().baseUrl(properties.catalogUrl().toString()).build();
    }

    Mono<ArchivedVideo> owned(String videoId, String username) {
        if (username == null || username.isBlank()) {
            return Mono.empty();
        }
        return client.get()
                .uri("/internal/v1/library/owned/{videoId}?username={username}", videoId, username)
                .retrieve()
                .bodyToMono(ArchivedVideo.class)
                .onErrorResume(WebClientResponseException.NotFound.class, error -> Mono.empty());
    }
}
