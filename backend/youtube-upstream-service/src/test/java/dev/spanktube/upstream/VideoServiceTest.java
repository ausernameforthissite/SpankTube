package dev.spanktube.upstream;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoServiceTest {

    @Test
    void refreshEvictsCachedStreamBeforeLoadingFreshMetadata() {
        MetadataCacheService cache = mock(MetadataCacheService.class);
        VideoService service = new VideoService(mock(UpstreamProvider.class), cache);
        VideoDetails expected = mock(VideoDetails.class);
        when(cache.evict("streams:v3", "dQw4w9WgXcQ")).thenReturn(reactor.core.publisher.Mono.empty());
        when(cache.get(eq("streams:v3"), eq("dQw4w9WgXcQ"), eq(VideoDetails.class),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<VideoDetails>>any(),
                org.mockito.ArgumentMatchers.<java.util.function.Function<VideoDetails, Duration>>any()))
                .thenReturn(reactor.core.publisher.Mono.just(expected));

        VideoDetails actual = service.refresh("dQw4w9WgXcQ").block();

        assertThat(actual).isSameAs(expected);
        InOrder order = inOrder(cache);
        order.verify(cache).evict("streams:v3", "dQw4w9WgXcQ");
        order.verify(cache).get(eq("streams:v3"), eq("dQw4w9WgXcQ"), eq(VideoDetails.class),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<VideoDetails>>any(),
                org.mockito.ArgumentMatchers.<java.util.function.Function<VideoDetails, Duration>>any());
    }
}
