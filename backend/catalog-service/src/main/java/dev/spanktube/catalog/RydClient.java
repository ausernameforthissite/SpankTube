package dev.spanktube.catalog;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class RydClient {

    private static final Logger log = LoggerFactory.getLogger(RydClient.class);

    private final RestClient client;

    RydClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl("https://returnyoutubedislikeapi.com").requestFactory(requests).build();
    }

    Long dislikes(String videoId) {
        try {
            RydResponse response = client.get()
                    .uri("/votes?videoId={videoId}", videoId)
                    .retrieve()
                    .body(RydResponse.class);
            return response == null ? null : response.dislikes();
        } catch (RestClientException error) {
            log.warn("Return YouTube Dislike is unavailable for {}: {}", videoId, error.getMessage());
            return null;
        }
    }

    record RydResponse(Long dislikes) {
    }
}
