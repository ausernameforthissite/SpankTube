package dev.spanktube.media;

import java.net.URI;

import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClientResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaRedirectCacheTest {

    private final MediaRedirectCache cache = new MediaRedirectCache();

    @Test
    void reusesSuccessfulRedirectTargetAndFollowsUpdatedChain() {
        URI original = URI.create("https://rr5.googlevideo.com/videoplayback?sig=original");
        URI intermediate = URI.create("https://rr2.googlevideo.com/videoplayback?sig=intermediate");
        URI target = URI.create("https://rr2.googlevideo.com/videoplayback?sig=final");
        cache.record(response(original, intermediate));
        cache.record(response(intermediate, target));

        assertThat(cache.resolve(original)).isEqualTo(target);
    }

    @Test
    void ignoresRedirectsAndRejectsUnexpectedHosts() {
        URI original = URI.create("https://rr5.googlevideo.com/videoplayback");
        URI target = URI.create("https://example.com/videoplayback");
        HttpClientResponse redirect = mock(HttpClientResponse.class);
        when(redirect.status()).thenReturn(HttpResponseStatus.FOUND);
        when(redirect.resourceUrl()).thenReturn(target.toString());
        when(redirect.redirectedFrom()).thenReturn(new String[]{original.toString()});
        cache.record(redirect);

        assertThat(cache.resolve(original)).isEqualTo(original);
        assertThat(cache.resolve(URI.create("https://example.com/video"))).isEqualTo(URI.create("https://example.com/video"));
    }

    @Test
    void invalidatesTargetAfterHttpError() {
        URI original = URI.create("https://rr5.googlevideo.com/videoplayback");
        URI target = URI.create("https://rr2.googlevideo.com/videoplayback");
        cache.record(response(original, target));

        cache.invalidate(target);

        assertThat(cache.resolve(original)).isEqualTo(original);
    }

    private static HttpClientResponse response(URI source, URI target) {
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.status()).thenReturn(HttpResponseStatus.OK);
        when(response.resourceUrl()).thenReturn(target.toString());
        when(response.redirectedFrom()).thenReturn(new String[]{source.toString()});
        return response;
    }
}
