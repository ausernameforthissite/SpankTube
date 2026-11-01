package dev.spanktube.media;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
class PlaybackLogService {

    private final Map<String, Sinks.Many<String>> sessions = new ConcurrentHashMap<>();

    Flux<ServerSentEvent<String>> stream(String videoId, String session) {
        if (!videoId.matches("[A-Za-z0-9_-]{11}") || session == null
                || !session.matches("[A-Za-z0-9-]{8,80}")) {
            return Flux.error(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid playback log session"));
        }
        String key = key(videoId, session);
        Sinks.Many<String> sink = Sinks.many().replay().limit(10);
        sessions.put(key, sink);
        sink.tryEmitNext("Ожидание запроса манифеста…");
        Flux<ServerSentEvent<String>> logs = sink.asFlux()
                .map(line -> ServerSentEvent.builder(line).event("playback-log").build());
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(tick -> ServerSentEvent.builder("keepalive").event("ping").build());
        return Flux.merge(logs, heartbeat).doFinally(signal -> {
            sessions.remove(key, sink);
            sink.tryEmitComplete();
        });
    }

    void log(String videoId, String session, String message) {
        if (session == null || message == null) {
            return;
        }
        Sinks.Many<String> sink = sessions.get(key(videoId, session));
        if (sink != null) {
            sink.tryEmitNext(message.length() > 500 ? message.substring(0, 500) : message);
        }
    }

    void error(String videoId, String session, Throwable error) {
        String message = error instanceof ResponseStatusException status && status.getReason() != null
                ? status.getReason() : error.getClass().getSimpleName();
        log(videoId, session, "Ошибка подготовки видео: " + message);
    }

    private static String key(String videoId, String session) {
        return videoId + "|" + session;
    }
}
