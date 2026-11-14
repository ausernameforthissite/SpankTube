package dev.spanktube.media;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaControllerTest {

    @Test
    void detectsHlsSegmentContentTypesFromActualContainers() {
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/seg.ts"))
                .toString()).isEqualTo("video/mp2t");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/seg.ts"), "audio")
                .toString()).isEqualTo("audio/aac");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/init.m4s"))
                .toString()).isEqualTo("video/mp4");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/init.mp4"))
                .toString()).isEqualTo("video/mp4");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/key.bin"))
                .toString()).isEqualTo("application/octet-stream");
    }

    private final UpstreamClient upstream = mock(UpstreamClient.class);

    @Test
    void refreshesStaleSignedUrlOnceAndDrainsTheFailedBody() {
        List<URI> targets = new CopyOnWriteArrayList<>();
        AtomicBoolean staleConsumed = new AtomicBoolean();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            targets.add(request.url());
            if (targets.size() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                        .body(Flux.just(buffer("stale")).doFinally(signal -> staleConsumed.set(true))).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.PARTIAL_CONTENT)
                    .header("Content-Type", "video/mp4")
                    .body(Flux.just(buffer("fresh"))).build());
        }).build();
        when(upstream.video("dQw4w9WgXcQ")).thenReturn(Mono.just(video("stale")));
        when(upstream.refresh("dQw4w9WgXcQ")).thenReturn(Mono.just(video("fresh")));

        ResponseEntity<Flux<DataBuffer>> response = controller(client).segment("dQw4w9WgXcQ", "135", new HttpHeaders()).block();

        assertThat(response.getStatusCode().value()).isEqualTo(206);
        assertThat(staleConsumed).isTrue();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(1).toString()).contains("sig=fresh");
        String content = DataBufferUtils.join(response.getBody()).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return new String(bytes, StandardCharsets.UTF_8);
        }).block();
        assertThat(content).isEqualTo("fresh");
        verify(upstream).video("dQw4w9WgXcQ");
        verify(upstream).refresh("dQw4w9WgXcQ");
    }

    @Test
    void returnsSecondForbiddenWithoutAnotherRefresh() {
        List<URI> targets = new CopyOnWriteArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            targets.add(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).body(Flux.empty()).build());
        }).build();
        when(upstream.video("dQw4w9WgXcQ")).thenReturn(Mono.just(video("stale")));
        when(upstream.refresh("dQw4w9WgXcQ")).thenReturn(Mono.just(video("fresh")));

        org.springframework.web.reactive.function.client.WebClientResponseException.Forbidden error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.reactive.function.client.WebClientResponseException.Forbidden.class,
                        () -> controller(client).segment("dQw4w9WgXcQ", "135", new HttpHeaders()).block());
        assertThat(error.getStatusCode().value()).isEqualTo(403);
        assertThat(targets).hasSize(2);
        verify(upstream).refresh("dQw4w9WgXcQ");
    }

    @Test
    void doesNotRefreshMetadataForOtherHttpErrors() {
        List<URI> targets = new CopyOnWriteArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            targets.add(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).body(Flux.empty()).build());
        }).build();
        when(upstream.video("dQw4w9WgXcQ")).thenReturn(Mono.just(video("stale")));

        org.springframework.web.reactive.function.client.WebClientResponseException.NotFound error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class,
                        () -> controller(client).segment("dQw4w9WgXcQ", "135", new HttpHeaders()).block());
        assertThat(error.getStatusCode().value()).isEqualTo(404);
        assertThat(targets).hasSize(1);
        verify(upstream, org.mockito.Mockito.never()).refresh("dQw4w9WgXcQ");
    }

    private MediaController controller(WebClient client) {
        org.springframework.beans.factory.ObjectProvider<dev.spanktube.egress.EgressProbe> egressProbe =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(egressProbe.getIfAvailable()).thenReturn(null);
        return new MediaController(upstream, mock(Mp4IndexService.class), mock(DashManifestGenerator.class), client,
                new dev.spanktube.egress.MediaRedirectCache(), egressProbe, mock(ArchivePlaybackService.class), mock(ThumbnailService.class),
                mock(PlaybackLogService.class), mock(ProgressiveDashService.class), mock(LiveDashService.class),
                mock(LiveHlsService.class));
    }

    private static UpstreamVideo video(String signature) {
        return new UpstreamVideo("dQw4w9WgXcQ", 10L, List.of(new UpstreamVideo.Format("135",
                "https://rr1.googlevideo.com/video?sig=" + signature, "video/mp4", "avc1.4D401E",
                640, 480, 25.0, 1000L, null, 5L, true, false, null, null)), List.of());
    }

    private static DataBuffer buffer(String value) {
        return new DefaultDataBufferFactory().wrap(value.getBytes(StandardCharsets.UTF_8));
    }
}
