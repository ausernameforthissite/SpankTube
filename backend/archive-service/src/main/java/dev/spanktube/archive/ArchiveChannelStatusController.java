package dev.spanktube.archive;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
class ArchiveChannelStatusController {

    private final ArchiveChannelStatusService statuses;

    ArchiveChannelStatusController(ArchiveChannelStatusService statuses) {
        this.statuses = statuses;
    }

    @GetMapping("/api/v1/archive/channel-status/{channelId}")
    Mono<ArchiveChannelStatusService.ChannelArchiveStatus> status(@PathVariable String channelId,
            HttpServletRequest request) {
        String username = request.getHeader("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        return statuses.status(channelId, username);
    }
}
