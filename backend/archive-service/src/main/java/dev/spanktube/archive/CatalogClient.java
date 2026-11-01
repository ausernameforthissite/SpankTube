package dev.spanktube.archive;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class CatalogClient {

    private final RestClient client;

    CatalogClient(@Value("${spanktube.catalog.url:http://localhost:8084}") String catalogUrl) {
        client = RestClient.builder().baseUrl(catalogUrl).build();
    }

    Optional<ArchiveModels.ExistingVideo> existing(String owner, String videoId) {
        try {
            ArchiveModels.ExistingVideo video = client.get()
                    .uri("/internal/v1/library/existing/{videoId}?username={username}", videoId, owner)
                    .retrieve()
                    .body(ArchiveModels.ExistingVideo.class);
            return Optional.ofNullable(video);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw error;
        }
    }

    boolean hasLink(String owner, String videoId) {
        try {
            client.get()
                    .uri("/internal/v1/library/links/{videoId}?username={username}", videoId, owner)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                return false;
            }
            throw error;
        }
    }

    void prepare(String username, long requiredBytes, String videoId) {
        client.post()
                .uri("/internal/v1/library/prepare")
                .body(new PrepareRequest(username, requiredBytes, videoId))
                .retrieve()
                .toBodilessEntity();
    }

    record PrepareRequest(String username, long requiredBytes, String videoId) {
    }
}
