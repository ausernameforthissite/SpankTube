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

    @ExceptionHandler({org.springframework.web.server.ResponseStatusException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> errorResponse(Exception error) {
        org.springframework.web.ErrorResponse response = (org.springframework.web.ErrorResponse) error;
        String message = response.getBody().getDetail() == null ? error.getMessage() : response.getBody().getDetail();
        return ResponseEntity.status(response.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception error) {
        org.slf4j.LoggerFactory.getLogger(ArchiveErrorHandler.class).error("Unhandled archive API error", error);
        return ResponseEntity.internalServerError().body(Map.of("message", "Internal archive error"));
    }
}
