package dev.spanktube.archive;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@Component
class ArchiveEvents {

    private final ArchiveRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicBoolean dispatchScheduled = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "archive-events");
        thread.setDaemon(true);
        return thread;
    });

    ArchiveEvents(ArchiveRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        executor.scheduleWithFixedDelay(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    SseEmitter register(String username, boolean admin) {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(username, admin, emitter);
        clients.put(id, client);
        emitter.onCompletion(() -> clients.remove(id));
        emitter.onTimeout(() -> clients.remove(id));
        emitter.onError(error -> clients.remove(id));
        send(client, "queue", snapshot(client));
        return emitter;
    }

    void publishQueueUpdate() {
        long requestedVersion = version.incrementAndGet();
        if (dispatchScheduled.compareAndSet(false, true)) {
            executor.schedule(() -> dispatch(requestedVersion), 50, TimeUnit.MILLISECONDS);
        }
    }

    private void dispatch(long dispatchedVersion) {
        clients.values().forEach(client -> send(client, "queue", snapshot(client)));
        dispatchScheduled.set(false);
        if (version.get() != dispatchedVersion) {
            publishQueueUpdate();
        }
    }

    private QueueSnapshot snapshot(Client client) {
        List<ArchiveModels.Job> jobs = repository.jobs(client.username(), client.admin(), null);
        return new QueueSnapshot(jobs, client.admin() ? 0 : repository.waitingAhead(client.username()));
    }

    private void heartbeat() {
        clients.values().forEach(client -> send(client, "ping", "keepalive"));
    }

    private void send(Client client, String event, Object data) {
        try {
            client.emitter().send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException error) {
            client.emitter().complete();
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
        clients.values().forEach(client -> client.emitter().complete());
        clients.clear();
    }

    private record Client(String username, boolean admin, SseEmitter emitter) {
    }

    private record QueueSnapshot(List<ArchiveModels.Job> jobs, long beforeYou) {
    }
}
