package dev.spanktube.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
class CatalogErrorHandler {

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiError> quota(QuotaExceededException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("QUOTA_EXCEEDED", error.getMessage()));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<String> downstream(RestClientResponseException error) {
        return ResponseEntity.status(error.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error.getResponseBodyAsString());
    }

    record ApiError(String code, String message) {
    }
}
