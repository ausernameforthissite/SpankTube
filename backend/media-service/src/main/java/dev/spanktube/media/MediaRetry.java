package dev.spanktube.media;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.util.retry.Retry;

final class MediaRetry {

    private MediaRetry() {
    }

    static Retry transientFailures() {
        return Retry.backoff(2, Duration.ofSeconds(1)).filter(MediaRetry::isTransientNetworkError);
    }

    private static boolean isTransientNetworkError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof WebClientRequestException || current instanceof TimeoutException
                    || current instanceof reactor.netty.http.client.PrematureCloseException) {
                return true;
            }
        }
        return false;
    }
}
