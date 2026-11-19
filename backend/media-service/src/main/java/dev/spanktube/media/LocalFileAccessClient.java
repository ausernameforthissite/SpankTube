package dev.spanktube.media;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
class LocalFileAccessClient {

    private final WebClient client;

    LocalFileAccessClient(MediaProperties properties) {
        client = WebClient.builder().baseUrl(properties.userServiceUrl().toString()).build();
    }

    Mono<Void> check(HttpHeaders headers) {
        String username = headers.getFirst("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        return client.get()
                .uri(builder -> builder.path("/internal/v1/users/{username}/local-files-access").build(username))
                .retrieve()
                .bodyToMono(LocalFileAccess.class)
                .flatMap(access -> access.enabled() ? Mono.empty()
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Local file access is not enabled for this user")))
                .then();
    }

    record LocalFileAccess(String username, boolean enabled) {
    }
}
