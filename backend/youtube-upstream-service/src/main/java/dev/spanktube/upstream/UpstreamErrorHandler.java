package dev.spanktube.upstream;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class UpstreamErrorHandler {

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<ApiError> upstream(UpstreamException error) {
        HttpStatus status = error.reason() == UpstreamException.Reason.EGRESS_UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        List<String> actions = error.reason() == UpstreamException.Reason.ANTIBOT
                ? List.of(
                        "Enable the bgutil PO-token provider for yt-dlp",
                        "Update yt-dlp to the latest release",
                        "Switch the UpstreamProvider adapter")
                : List.of();
        return ResponseEntity.status(status)
                .body(new ApiError(error.reason().name(), error.getMessage(), actions));
    }

    record ApiError(String code, String message, List<String> actions) {
    }
}
