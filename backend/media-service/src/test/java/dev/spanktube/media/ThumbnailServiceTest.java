package dev.spanktube.media;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import dev.spanktube.egress.EgressProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThumbnailServiceTest {

    @Test
    void loadsSmallThumbnailDirectlyWithoutMetadataLookup() {
        assertThat(ThumbnailService.thumbnailUrls("3TYZjVzWazE", "hq"))
                .containsExactly("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg",
                        "https://i.ytimg.com/vi/3TYZjVzWazE/mqdefault.jpg",
                        "https://i.ytimg.com/vi/3TYZjVzWazE/default.jpg");
    }

    @Test
    void fallsBackFromMaximumResolution() {
        assertThat(ThumbnailService.thumbnailUrls("3TYZjVzWazE", "max").getFirst())
                .endsWith("/maxresdefault.jpg");
    }

    @Test
    void sharesConcurrentImageRequestAndCachesResult() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.delay(Duration.ofMillis(50)).thenReturn(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE).body("image").build());
        }).build();
        @SuppressWarnings("unchecked")
        ObjectProvider<EgressProbe> probes = mock(ObjectProvider.class);
        ThumbnailService service = new ThumbnailService(client, probes, new ImageCache());
        String url = "https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg";

        var responses = Mono.zip(service.image(url), service.image(url)).block(Duration.ofSeconds(1));

        assertThat(responses.getT1().getBody()).isEqualTo("image".getBytes());
        assertThat(responses.getT2().getBody()).isEqualTo("image".getBytes());
        ResponseEntity<byte[]> cached = service.image(url).block(Duration.ofSeconds(1));
        assertThat(cached.getBody()).isEqualTo("image".getBytes());
        assertThat(requests).hasValue(1);
    }

    @Test
    void imageCacheReusesStoredResponse() {
        ImageCache cache = new ImageCache();
        byte[] body = { 1, 2, 3 };
        cache.put("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg", body, MediaType.IMAGE_JPEG);

        assertThat(cache.get("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg").body())
                .isSameAs(body);
    }
}
