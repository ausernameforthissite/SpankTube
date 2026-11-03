package dev.spanktube.archive;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class SponsorBlockClient {

    private static final String CATEGORIES = "[\"sponsor\",\"selfpromo\",\"exclusive_access\",\"interaction\","
            + "\"intro\",\"outro\",\"preview\",\"music_offtopic\",\"poi_highlight\",\"filler\"]";

    private final RestClient client;

    SponsorBlockClient(@Value("${spanktube.sponsorblock.url:https://sponsor.ajay.app}") String baseUrl) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    List<Segment> segments(String videoId) {
        try {
            List<Map<String, Object>> response = client.get()
                    .uri("/api/skipSegments?videoID={videoId}&categories={categories}", videoId, CATEGORIES)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            if (response == null) {
                return List.of();
            }
            return response.stream().map(SponsorBlockClient::segment)
                    .filter(java.util.Objects::nonNull).toList();
        } catch (org.springframework.web.client.RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                return List.of();
            }
            throw new ArchiveDownloadException("SponsorBlock failed: " + error.getMessage(), error);
        } catch (RestClientException error) {
            throw new ArchiveDownloadException("SponsorBlock failed: " + error.getMessage(), error);
        }
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
