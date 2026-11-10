package dev.spanktube.archive;

import dev.spanktube.events.EventSubscriber;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
class WatchProgressHandler {

    private final EventSubscriber subscriber;
    private final ArchiveJobService jobs;
    private final CatalogClient catalogClient;
    private final UserClient userClient;

    WatchProgressHandler(EventSubscriber subscriber, ArchiveJobService jobs,
            CatalogClient catalogClient, UserClient userClient) {
        this.subscriber = subscriber;
        this.jobs = jobs;
        this.catalogClient = catalogClient;
        this.userClient = userClient;
    }

    @PostConstruct
    void subscribe() {
        subscriber.subscribe("archive.watch.progress", "watch.progress", WatchProgressEvent.class,
                event -> archiveIfNeeded(event.payload())).subscribe();
    }

    Mono<Void> archiveIfNeeded(WatchProgressEvent progress) {
        if (!userClient.autoArchive(progress.username())) {
            return Mono.empty();
        }
        if (progress.positionSeconds() < 30 && progress.watchPercent() < 5) {
            return Mono.empty();
        }
        if (catalogClient.existing(progress.username(), progress.videoId()).isPresent()) {
            return Mono.empty();
        }
        String role = userClient.role(progress.username());
        jobs.enqueue(new ArchiveModels.EnqueueRequest(progress.username(), role,
                ArchiveModels.Kind.VIDEO, progress.videoId(), java.util.List.of(progress.videoId())));
        return Mono.empty();
    }

    public record WatchProgressEvent(String username, String videoId,
            long positionSeconds, long durationSeconds, double watchPercent) {
    }
}
