package dev.spanktube.archive;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Component
class ArchivePreflightEvents {

    private final ObjectMapper objectMapper;
    private final Map<String, SseEmitter> clients = new ConcurrentHashMap<>();

    ArchivePreflightEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SseEmitter register(ArchivePreflightService.Progress progress) {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        clients.put(id, emitter);
        Runnable[] unsubscribe = { () -> { } };
        boolean[] terminal = { false };
        Runnable cleanup = () -> {
            clients.remove(id);
            unsubscribe[0].run();
        };
        Runnable disconnect = () -> {
            cleanup.run();
            if (!terminal[0]) {
                progress.cancel();
            }
        };
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(error -> disconnect.run());
        unsubscribe[0] = progress.subscribe(event -> {
            if (isTerminal(event.progress().status())) {
                terminal[0] = true;
            }
            send(emitter, event.name(), event.progress());
            if (terminal[0]) {
                emitter.complete();
            }
        });
        if (!clients.containsKey(id)) {
            unsubscribe[0].run();
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        clients.values().forEach(emitter -> send(emitter, "ping", "keepalive"));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data(json));
            }
        } catch (IOException | IllegalStateException error) {
            emitter.complete();
        }
    }

    private static boolean isTerminal(String status) {
        return status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED");
    }

    @PreDestroy
    void close() {
        clients.values().forEach(SseEmitter::complete);
        clients.clear();
    }
}
