package dev.spanktube.catalog;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventSubscriber;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
class ArchiveCompletedHandler {

    private final EventSubscriber subscriber;
    private final LibraryService libraryService;

    ArchiveCompletedHandler(EventSubscriber subscriber, LibraryService libraryService) {
        this.subscriber = subscriber;
        this.libraryService = libraryService;
    }

    @PostConstruct
    void subscribe() {
        subscriber.subscribe("catalog.video.archived", "video.archived", ArchiveCompletedEvent.class,
                event -> {
                    libraryService.register(event.payload());
                    return Mono.empty();
                }).subscribe();
    }
}
