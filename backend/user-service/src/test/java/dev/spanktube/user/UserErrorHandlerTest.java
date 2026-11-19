package dev.spanktube.user;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class UserErrorHandlerTest {

    @Test
    void exposesIntentionalStatusReasonAsApiMessage() {
        var response = new UserErrorHandler().responseStatus(new ResponseStatusException(HttpStatus.GONE,
                "This invite has expired"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("message", "This invite has expired");
    }
}
