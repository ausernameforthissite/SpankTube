package dev.spanktube.media;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaRetryTest {

    @Test
    void retriesTransientConnectionFailures() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> result = Mono.<String>defer(() -> attempts.incrementAndGet() < 3
                        ? Mono.<String>error(new WebClientRequestException(
                                new java.io.IOException("handshake failed"), HttpMethod.GET,
                                URI.create("https://rr1.googlevideo.com/videoplayback"), HttpHeaders.EMPTY))
                        : Mono.just("ok"))
                .retryWhen(MediaRetry.transientFailures());

        assertThat(result.block(Duration.ofSeconds(10))).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryHttpErrors() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> result = Mono.<String>defer(() -> {
                    attempts.incrementAndGet();
                    return Mono.<String>error(WebClientResponseException.create(403, "Forbidden", HttpHeaders.EMPTY,
                            new byte[0], StandardCharsets.UTF_8));
                })
                .retryWhen(MediaRetry.transientFailures());

        assertThatThrownBy(() -> result.block(Duration.ofSeconds(2)))
                .isInstanceOf(WebClientResponseException.class);
        assertThat(attempts).hasValue(1);
    }
}
