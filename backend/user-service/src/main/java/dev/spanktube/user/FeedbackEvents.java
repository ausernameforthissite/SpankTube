package dev.spanktube.user;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class FeedbackEvents {

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final JdbcTemplate jdbc;

    FeedbackEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SseEmitter register(String username, boolean admin) {
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(username, admin, emitter);
        clients.add(client);
        Runnable remove = () -> clients.remove(client);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        send(client, "ready", "{}");
        return emitter;
    }

    void publish(UUID ticketId) {
        Set<String> recipients = Set.copyOf(jdbc.queryForList(
                "select username from feedback_ticket_access where ticket_id = ?", String.class, ticketId));
        String json = "{\"ticketId\":\"" + ticketId + "\"}";
        for (Client client : clients) {
            if (client.admin() || recipients.contains(client.username())) {
                send(client, "ticket", json);
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        clients.forEach(client -> send(client, "ping", "{}"));
    }

    private void send(Client client, String event, String data) {
        try {
            client.emitter().send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException error) {
            clients.remove(client);
        }
    }

    private record Client(String username, boolean admin, SseEmitter emitter) {
    }
}
