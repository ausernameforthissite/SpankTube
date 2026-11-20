package dev.spanktube.archive;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
class ArchivePreflightController {

    private final ArchivePreflightService preflights;

    ArchivePreflightController(ArchivePreflightService preflights) {
        this.preflights = preflights;
    }

    @PostMapping("/api/v1/archive/estimate")
    ResponseEntity<ArchivePreflightService.ProgressSnapshot> estimate(@RequestBody EstimateRequest request,
            HttpServletRequest httpRequest) {
        String username = username(httpRequest);
        ArchiveModels.Kind kind;
        try {
            kind = ArchiveModels.Kind.valueOf(request.kind());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown archive kind");
        }
        if (request.targetHeight() == null) {
            return ResponseEntity.accepted().body(preflights.start(kind, request.targetId(), username, request.estimateId(),
                    request.filter()));
        }
        Integer targetHeight = null;
        if (!request.targetHeight().isNull()) {
            JsonNode value = request.targetHeight();
            long height = value.isIntegralNumber() ? value.longValue() : -1;
            if (height < 1 || height > 10_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Archive target height must be between 1 and 10000");
            }
            targetHeight = (int) height;
        }
        return ResponseEntity.accepted().body(preflights.start(kind, request.targetId(), username, request.estimateId(),
                request.filter(), targetHeight));
    }

    @GetMapping("/api/v1/archive/estimate/{estimateId}")
    ArchivePreflightService.ProgressSnapshot status(@PathVariable String estimateId, HttpServletRequest request) {
        return preflights.status(username(request), estimateId);
    }

    @GetMapping(value = "/api/v1/archive/estimate/{estimateId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String estimateId, HttpServletRequest request) {
        return preflights.events(username(request), estimateId);
    }

    @DeleteMapping("/api/v1/archive/estimate/{estimateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable String estimateId, HttpServletRequest request) {
        preflights.cancel(username(request), estimateId);
    }

    private static String username(HttpServletRequest request) {
        String username = request.getHeader("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        return username;
    }

    record EstimateRequest(String kind, String targetId, String estimateId, ArchiveFilter filter, JsonNode targetHeight) {
    }
}
