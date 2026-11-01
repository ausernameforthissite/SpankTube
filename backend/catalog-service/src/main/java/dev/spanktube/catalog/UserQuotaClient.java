package dev.spanktube.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class UserQuotaClient {

    private final RestClient client;

    UserQuotaClient(@Value("${spanktube.user.url:http://localhost:8083}") String userServiceUrl) {
        client = RestClient.builder().baseUrl(userServiceUrl).build();
    }

    LibraryModels.QuotaResponse resolve(String username) {
        return client.get()
                .uri("/internal/v1/users/{username}/quota", username)
                .retrieve()
                .body(LibraryModels.QuotaResponse.class);
    }
}
