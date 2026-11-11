package dev.spanktube.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class SponsorBlockClient {

    private static final Logger log = LoggerFactory.getLogger(SponsorBlockClient.class);

    private final RestClient client;

    SponsorBlockClient(@Value("${spanktube.sponsorblock.url:https://sponsor.ajay.app}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    List<Segment> segments(String videoId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Map<String, Object>> response = client.get()
                        .uri("/api/skipSegments?videoID={videoId}&categories={categories}", videoId, "[\"sponsor\"]")
                        .retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<>() { });
                if (response == null) {
                    return List.of();
                }
                return response.stream().map(SponsorBlockClient::segment)
                        .filter(java.util.Objects::nonNull).toList();
            } catch (RestClientException error) {
                boolean transientFailure = error instanceof org.springframework.web.client.ResourceAccessException
                        || error instanceof org.springframework.web.client.RestClientResponseException responseError
                        && responseError.getStatusCode().is5xxServerError();
                if (attempt == 2 || !transientFailure) {
                    log.warn("SponsorBlock is unavailable for {}: {}", videoId, error.getMessage());
                    return List.of();
                }
                log.debug("Retrying SponsorBlock for {} after {}", videoId, error.getMessage());
                try {
                    Thread.sleep(Duration.ofMillis(250));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("SponsorBlock request for {} was interrupted", videoId);
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private static Segment segment(Map<String, Object> data) {
        if (!(data.get("segment") instanceof List<?> range) || range.size() != 2
                || !(range.get(0) instanceof Number start) || !(range.get(1) instanceof Number end)) {
            return null;
        }
        if (!Double.isFinite(start.doubleValue()) || !Double.isFinite(end.doubleValue())
                || end.doubleValue() <= start.doubleValue()) {
            return null;
        }
        return new Segment(String.valueOf(data.get("category")), start.doubleValue(), end.doubleValue());
    }

    record Segment(String category, double startSeconds, double endSeconds) {
    }
}
