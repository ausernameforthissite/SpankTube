package dev.spanktube.events;

import java.util.function.Function;

import reactor.core.publisher.Mono;

public interface EventSubscriber {

    <T> Mono<Void> subscribe(String queue, String eventType, Class<T> payloadType,
            Function<DomainEvent<T>, Mono<Void>> handler);
}
