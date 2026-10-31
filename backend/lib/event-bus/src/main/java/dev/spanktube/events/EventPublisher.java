package dev.spanktube.events;

import reactor.core.publisher.Mono;

public interface EventPublisher {

    <T> Mono<Void> publish(DomainEvent<T> event);
}
