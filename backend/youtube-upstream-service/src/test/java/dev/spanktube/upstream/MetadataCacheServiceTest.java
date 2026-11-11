package dev.spanktube.upstream;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.spanktube.egress.EgressProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataCacheServiceTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final MetadataCacheService cache = new MetadataCacheService(redis, new ObjectMapper(),
            new EgressProperties(EgressProperties.Mode.DIRECT, "", 1080, "", "", true, null));

    @Test
    void coalescesConcurrentLoadsAndReadsCachedValue() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(reactor.core.publisher.Mono.empty());
        when(values.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(reactor.core.publisher.Mono.just(true));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        SearchResults expected = new SearchResults(List.of(), null);

        reactor.core.publisher.Mono<SearchResults> first = cache.get("search:videos", "query:first",
                SearchResults.class, () -> load(expected, loads, started, release), Duration.ofMinutes(5));
        reactor.core.publisher.Mono<SearchResults> second = cache.get("search:videos", "query:first",
                SearchResults.class, () -> load(expected, loads, started, release), Duration.ofMinutes(5));
        CompletableFuture<SearchResults> firstResult = new CompletableFuture<>();
        CompletableFuture<SearchResults> secondResult = new CompletableFuture<>();
        first.subscribe(firstResult::complete, firstResult::completeExceptionally);
        second.subscribe(secondResult::complete, secondResult::completeExceptionally);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        assertThat(firstResult.get(2, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(secondResult.get(2, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(loads).hasValue(1);
        verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));

        String json = new ObjectMapper().writeValueAsString(expected);
        when(values.get(anyString())).thenReturn(reactor.core.publisher.Mono.just(json));
        assertThat(cache.get("search:videos", "query:first", SearchResults.class, () -> {
            throw new AssertionError("Cached value must not be loaded again");
        }, Duration.ofMinutes(5)).block()).isEqualTo(expected);
    }

    private static SearchResults load(SearchResults expected, AtomicInteger loads,
            CountDownLatch started, CountDownLatch release) {
        loads.incrementAndGet();
        started.countDown();
        try {
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to finish the shared load");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
        return expected;
    }
}
