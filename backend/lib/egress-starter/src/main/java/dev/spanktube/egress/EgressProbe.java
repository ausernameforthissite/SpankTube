package dev.spanktube.egress;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public final class EgressProbe implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EgressProbe.class);

    private final WebClient webClient;
    private final EgressProperties properties;
    private final AtomicInteger available = new AtomicInteger();
    private final AtomicReference<String> lastError = new AtomicReference<>("not checked");
    private final Timer latency;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "youtube-egress-probe");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> schedule;
    private volatile boolean running;

    EgressProbe(WebClient webClient, EgressProperties properties, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.properties = properties;
        this.latency = Timer.builder("upstream_egress_probe_latency")
                .tag("mode", properties.mode().name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
        Gauge.builder("upstream_egress_ok", available, AtomicInteger::get)
                .tag("mode", properties.mode().name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
    }

    public boolean isAvailable() {
        return available.get() == 1;
    }

    public String lastError() {
        return lastError.get();
    }

    public Mono<Boolean> probe() {
        Timer.Sample sample = Timer.start();
        return webClient.get()
                .uri(properties.probe().uri())
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().value() == 204))
                .timeout(properties.probe().timeout())
                .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(EgressProbe::isTransientNetworkError))
                .map(success -> {
                    sample.stop(latency);
                    available.set(success ? 1 : 0);
                    lastError.set(success ? "" : "generate_204 did not return HTTP 204");
                    return success;
                })
                .onErrorResume(error -> {
                    sample.stop(latency);
                    available.set(0);
                    lastError.set(error.getClass().getSimpleName() + ": " + error.getMessage());
                    return Mono.just(false);
                });
    }

    private static boolean isTransientNetworkError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof reactor.netty.http.client.PrematureCloseException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        long intervalMillis = properties.probe().interval().toMillis();
        schedule = scheduler.scheduleWithFixedDelay(this::probeSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void probeSafely() {
        try {
            Duration wait = properties.probe().timeout().multipliedBy(3).plusSeconds(4);
            Boolean success = probe().block(wait);
            if (!Boolean.TRUE.equals(success) && log.isWarnEnabled()) {
                log.warn("YouTube egress probe failed: {}", lastError());
            }
        } catch (RuntimeException error) {
            available.set(0);
            lastError.set(error.getMessage());
            log.warn("YouTube egress probe failed: {}", error.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (schedule != null) {
            schedule.cancel(false);
        }
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
