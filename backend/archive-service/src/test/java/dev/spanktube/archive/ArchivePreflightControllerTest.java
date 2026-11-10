package dev.spanktube.archive;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchivePreflightControllerTest {

    private final ArchivePreflightService preflights = mock(ArchivePreflightService.class);
    private final ArchivePreflightController controller = new ArchivePreflightController(preflights);

    @Test
    void returnsProgressOnlyForItsOwner() {
        ArchivePreflightService.ProgressSnapshot snapshot = new ArchivePreflightService.ProgressSnapshot(
                "estimate-id", ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "ESTIMATING",
                2, 1, 1, 0, 1250, 625, 1875, 2, 2000, 1000,
                2, 1, 2, 1, 1.5, null, Instant.now(), Instant.now());
        when(preflights.status("viewer", "estimate-id")).thenReturn(snapshot);
        MockHttpServletRequest request = request();

        ArchivePreflightService.ProgressSnapshot result = controller.status("estimate-id", request);

        assertThat(result.status()).isEqualTo("ESTIMATING");
        assertThat(result.processedVideoCount()).isEqualTo(1);
        assertThat(result.remainingOnPage()).isEqualTo(1);
        assertThat(result.videosPerSecond()).isEqualTo(1.5);
        assertThat(result.totalBytes()).isEqualTo(1875);
    }

    @Test
    void acceptsClientEstimateId() {
        ArchivePreflightService.Estimate estimate = mock(ArchivePreflightService.Estimate.class);
        when(preflights.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "client-id"))
                .thenReturn(Mono.just(estimate));

        ResponseEntity<ArchivePreflightService.Estimate> result = controller.estimate(
                new ArchivePreflightController.EstimateRequest("PLAYLIST", "playlist", "client-id"), request()).block();

        assertThat(result).isNotNull();
        assertThat(result.getBody()).isSameAs(estimate);
    }

    @Test
    void returnsNoContentWhenEstimateWasCancelled() {
        when(preflights.estimate(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "client-id"))
                .thenReturn(Mono.empty());

        ResponseEntity<ArchivePreflightService.Estimate> result = controller.estimate(
                new ArchivePreflightController.EstimateRequest("PLAYLIST", "playlist", "client-id"), request()).block();

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void streamsAndCancelsOnlyOwnerEstimate() {
        SseEmitter emitter = mock(SseEmitter.class);
        when(preflights.events("viewer", "estimate-id")).thenReturn(emitter);
        MockHttpServletRequest request = request();

        assertThat(controller.events("estimate-id", request)).isSameAs(emitter);
        controller.cancel("estimate-id", request);

        verify(preflights).cancel("viewer", "estimate-id");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-SpankTube-User", "viewer");
        return request;
    }
}
