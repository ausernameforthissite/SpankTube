package dev.spanktube.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class CatalogErrorHandler {

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ApiError> quota(QuotaExceededException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("QUOTA_EXCEEDED", error.getMessage()));
    }

    record ApiError(String code, String message) {
    }
}
