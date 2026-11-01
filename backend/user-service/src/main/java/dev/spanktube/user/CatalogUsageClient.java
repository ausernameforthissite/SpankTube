package dev.spanktube.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class CatalogUsageClient {

    private final RestClient client;

    CatalogUsageClient(@Value("${spanktube.catalog.url:http://localhost:8084}") String catalogUrl) {
        client = RestClient.builder().baseUrl(catalogUrl).build();
    }

    long usedBytes(String username) {
        return client.get()
                .uri("/internal/v1/library/usage/{username}", username)
                .retrieve()
                .body(UsageResponse.class)
                .usedBytes();
    }

    record UsageResponse(long usedBytes) {
    }
}
