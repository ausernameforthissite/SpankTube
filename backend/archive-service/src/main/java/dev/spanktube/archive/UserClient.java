package dev.spanktube.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class UserClient {

    private final RestClient client;

    UserClient(@Value("${spanktube.user.url:http://localhost:8083}") String userServiceUrl) {
        client = RestClient.builder().baseUrl(userServiceUrl).build();
    }

    String role(String username) {
        QuotaResponse response = client.get()
                .uri("/internal/v1/users/{username}/quota", username)
                .retrieve()
                .body(QuotaResponse.class);
        return response.role();
    }

    boolean autoArchive(String username) {
        SettingsResponse response = client.get()
                .uri("/internal/v1/users/{username}/settings", username)
                .retrieve()
                .body(SettingsResponse.class);
        return !Boolean.FALSE.equals(response.autoArchive());
    }

    record QuotaResponse(String role) {
    }

    record SettingsResponse(Boolean autoArchive) {
    }
}
