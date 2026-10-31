package dev.spanktube.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEvent<T>(String eventId, String eventType, Instant occurredAt, T payload) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    public static <T> DomainEvent<T> of(String eventType, T payload) {
        return new DomainEvent<>(UUID.randomUUID().toString(), eventType, Instant.now(), payload);
    }
}
