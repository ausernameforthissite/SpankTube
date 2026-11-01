package dev.spanktube.catalog;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
        long positionSeconds = Math.max(0, request.positionSeconds());
        double percent = request.durationSeconds() > 0
                ? Math.min(100, positionSeconds * 100.0 / request.durationSeconds()) : 0;
        repository.updateProgress(username, videoId, positionSeconds, percent);
        WatchProgressEvent event = new WatchProgressEvent(username, videoId,
                positionSeconds, request.durationSeconds(), percent);
        return publisher.publish(DomainEvent.of("watch.progress", event))
                .thenReturn(ResponseEntity.accepted().build());
    }

    @GetMapping("/api/v1/library/progress")
    java.util.List<LibraryModels.ProgressEntry> progress(HttpServletRequest servletRequest) {
        return repository.progress(identityService.required(servletRequest).username());
    }

    record WatchProgressRequest(long positionSeconds, long durationSeconds) {
    }

    public record WatchProgressEvent(String username, String videoId,
            long positionSeconds, long durationSeconds, double watchPercent) {
    }
}
