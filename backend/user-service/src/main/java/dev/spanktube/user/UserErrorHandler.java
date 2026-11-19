package dev.spanktube.user;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class UserErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException error) {
        String reason = error.getReason();
        String message = reason == null || reason.isBlank() ? error.getStatusCode().toString() : reason;
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }
}
