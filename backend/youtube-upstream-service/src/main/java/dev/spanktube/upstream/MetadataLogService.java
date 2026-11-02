package dev.spanktube.upstream;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
class MetadataLogService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_SESSIONS = 256;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    Flux<ServerSentEvent<String>> stream(String session) {
        if (!valid(session)) {
            return Flux.empty();
        }
        Entry entry = entry(session);
        Flux<ServerSentEvent<String>> logs = entry.sink.asFlux()
                .map(line -> ServerSentEvent.builder(line).event("playback-log").build());
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(tick -> ServerSentEvent.builder("keepalive").event("ping").build());
        return Flux.merge(logs, heartbeat);
    }

    void log(String session, String message) {
        if (!valid(session) || message == null) {
            return;
        }
        Entry entry = entry(session);
        entry.sink.tryEmitNext(message.length() > 500 ? message.substring(0, 500) : message);
    }

    void error(String session, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        log(session, "Ошибка получения метаданных: " + message);
    }

    private Entry entry(String session) {
        cleanup();
        Entry entry = sessions.computeIfAbsent(session, key -> {
            Entry created = new Entry(Sinks.many().replay().limit(10));
            created.sink.tryEmitNext("Ожидание метаданных…");
            return created;
        });
        entry.lastUsed = System.nanoTime();
        return entry;
    }

    private void cleanup() {
        long now = System.nanoTime();
        sessions.entrySet().removeIf(entry -> now - entry.getValue().lastUsed >= TTL.toNanos());
        while (sessions.size() > MAX_SESSIONS) {
            String eldest = sessions.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().lastUsed))
                    .map(Map.Entry::getKey).orElse(null);
            if (eldest == null) {
                return;
            }
            sessions.remove(eldest);
        }
    }

    private static boolean valid(String session) {
        return session != null && session.matches("[A-Za-z0-9-]{8,80}");
    }

    private static final class Entry {
        private final Sinks.Many<String> sink;
        private volatile long lastUsed;

        private Entry(Sinks.Many<String> sink) {
            this.sink = sink;
            this.lastUsed = System.nanoTime();
        }
    }
}
