package dev.spanktube.archive;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
class ArchiveErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(error.getMessage())));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<String> downstream(RestClientResponseException error) {
        return ResponseEntity.status(error.getStatusCode()).body(error.getResponseBodyAsString());
    }
}
