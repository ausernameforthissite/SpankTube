package dev.spanktube.archive;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
                2, 1, 2, 1, 1.5, null, Instant.now(), Instant.now(), 0, java.util.List.of());
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
    void startsEstimateAndReturnsAcceptedProgressImmediately() {
        ArchivePreflightService.ProgressSnapshot snapshot = new ArchivePreflightService.ProgressSnapshot(
                "client-id", ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "COLLECTING",
                0, 0, 0, 0, 0, 0, 0, 0, 2000, 1000,
                0, 0, 0, 0, 0, null, Instant.now(), Instant.now(), 0, java.util.List.of());
        when(preflights.start(ArchiveModels.Kind.PLAYLIST, "playlist", "viewer", "client-id")).thenReturn(snapshot);

        ResponseEntity<ArchivePreflightService.ProgressSnapshot> result = controller.estimate(
                new ArchivePreflightController.EstimateRequest("PLAYLIST", "playlist", "client-id"), request());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isSameAs(snapshot);
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
