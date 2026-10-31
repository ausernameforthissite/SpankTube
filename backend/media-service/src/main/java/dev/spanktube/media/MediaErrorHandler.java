package dev.spanktube.media;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestControllerAdvice
class MediaErrorHandler {

    @ExceptionHandler(WebClientResponseException.class)
    ResponseEntity<String> upstreamError(WebClientResponseException error) {
        return ResponseEntity.status(error.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error.getResponseBodyAsString());
    }
}
