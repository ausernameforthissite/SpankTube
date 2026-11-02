package dev.spanktube.catalog;

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

    Map<String, Object> video(String videoId, String logSession) {
        return client.get()
                .uri("/api/v1/videos/{videoId}?logSession={logSession}", videoId, logSession)
                .retrieve()
                .body(Map.class);
    }
}
