package dev.spanktube.archive;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveErrorHandlerTest {

    private final ArchiveErrorHandler handler = new ArchiveErrorHandler();

    @Test
    void returnsBadRequestWhenEstimateIdIsMissing() {
        var response = handler.errorResponse(new MissingServletRequestParameterException("estimateId", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Required parameter 'estimateId' is not present.");
    }

    @Test
    void preservesArchiveRejectionStatus() {
        var response = handler.errorResponse(new ResponseStatusException(HttpStatus.CONFLICT,
                "Active live streams cannot be archived or downloaded"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Active live streams cannot be archived or downloaded");
    }
}
