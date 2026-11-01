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

    Mono<ArchivedVideo> archived(String videoId, String username, String versionId) {
        if (username == null || username.isBlank()) {
            return Mono.empty();
        }
        if (versionId == null || versionId.isBlank()) {
            return client.get()
                    .uri("/internal/v1/library/existing/{videoId}?username={username}", videoId, username)
                    .retrieve()
                    .bodyToMono(ArchivedVideo.class)
                    .onErrorResume(WebClientResponseException.NotFound.class, error -> Mono.empty());
        }
        return client.get()
                .uri("/internal/v1/library/versions/{versionId}?username={username}&videoId={videoId}",
                        versionId, username, videoId)
                .retrieve()
                .bodyToMono(ArchivedVideo.class)
                .onErrorResume(WebClientResponseException.NotFound.class, error -> Mono.empty());
    }
}
