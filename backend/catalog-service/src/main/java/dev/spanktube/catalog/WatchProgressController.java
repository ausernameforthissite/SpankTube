package dev.spanktube.catalog;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class WatchProgressController {

    private final EventPublisher publisher;
    private final CatalogRepository repository;
    private final IdentityService identityService;

    WatchProgressController(EventPublisher publisher, CatalogRepository repository, IdentityService identityService) {
        this.publisher = publisher;
        this.repository = repository;
        this.identityService = identityService;
    }

    @PostMapping("/api/v1/videos/{videoId}/progress")
    Mono<ResponseEntity<Void>> progress(@PathVariable String videoId,
            @RequestBody WatchProgressRequest request, HttpServletRequest servletRequest) {
        String username = identityService.required(servletRequest).username();
        double percent = request.durationSeconds() > 0
                ? Math.min(100, request.positionSeconds() * 100.0 / request.durationSeconds()) : 0;
        repository.updateProgress(username, videoId, request.positionSeconds(), percent);
        WatchProgressEvent event = new WatchProgressEvent(username, videoId,
                request.positionSeconds(), request.durationSeconds(), percent);
        return publisher.publish(DomainEvent.of("watch.progress", event))
                .thenReturn(ResponseEntity.accepted().build());
    }

    record WatchProgressRequest(long positionSeconds, long durationSeconds) {
    }

    public record WatchProgressEvent(String username, String videoId,
            long positionSeconds, long durationSeconds, double watchPercent) {
    }
}
