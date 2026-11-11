package dev.spanktube.media;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.spanktube.egress.MediaRedirectCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProgressiveDashServiceTest {

    @TempDir
    Path directory;

    @Test
    void servesEarlyManifestWhileRemuxFinishesAndCachesFinalSegments() throws Exception {
        UpstreamVideo video = video();
        UpstreamClient upstream = mock(UpstreamClient.class);
        when(upstream.video(video.videoId())).thenReturn(Mono.just(video));
        TestService service = new TestService(upstream, directory);

        String first = service.manifest(video, "test").block(java.time.Duration.ofSeconds(5));
        assertThat(first).contains("type=\"dynamic\"");
        assertThat(first).contains("mediaPresentationDuration=\"PT6M30S\"");
        assertThat(service.downloads).hasValue(1);
        assertThat(service.remuxes).hasValue(1);

        service.finishRemux.countDown();
        assertThat(service.completed.await(5, TimeUnit.SECONDS)).isTrue();
        String second = service.manifest(video, "test").block(java.time.Duration.ofSeconds(5));
        assertThat(second).isEqualTo("<MPD type=\"static\"/>");
        assertThat(service.downloads).hasValue(1);
        assertThat(service.remuxes).hasValue(1);
        ResponseEntity<FileSystemResource> response = service.file(video.videoId(), "init-stream0.m4s").block();
        assertThat(response).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
    }

    @Test
    void resumesIncompleteManifestAfterRestart() throws Exception {
        UpstreamVideo video = video();
        UpstreamClient upstream = mock(UpstreamClient.class);
        when(upstream.video(video.videoId())).thenReturn(Mono.just(video));
        Files.createDirectories(directory.resolve("remux"));
        Files.writeString(directory.resolve("manifest.mpd"), TestService.EARLY_MANIFEST);
        for (String filename : List.of("init-0.m4s", "init-1.m4s", "chunk-0-00001.m4s", "chunk-1-00001.m4s")) {
            Files.writeString(directory.resolve("remux").resolve(filename), "partial");
        }
        TestService service = new TestService(upstream, directory);

        String manifest = service.manifest(video, "test").block(java.time.Duration.ofSeconds(5));

        assertThat(manifest).contains("type=\"dynamic\"");
        assertThat(service.downloads).hasValue(1);
        service.finishRemux.countDown();
        assertThat(service.completed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resumesPartialDownloadAndRetriesInterruptedChunk() throws Exception {
        byte[] content = "0123456789ABCDEF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<String> ranges = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            if (!"bytes=7-8388614".equals(range)) {
                byte[] message = String.valueOf(range).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, message.length);
                exchange.getResponseBody().write(message);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Range", "bytes 7-15/16");
            exchange.sendResponseHeaders(206, content.length - 7);
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseBody().write(content, 7, 4);
            } else {
                exchange.getResponseBody().write(content, 7, content.length - 7);
            }
            exchange.close();
        });
        server.start();
        try {
            UpstreamClient upstream = mock(UpstreamClient.class);
            ProgressiveDashService service = new ProgressiveDashService(
                    org.springframework.web.reactive.function.client.WebClient.builder().build(), upstream,
                    mock(PlaybackLogService.class), directory.toString(), "ffmpeg", new MediaRedirectCache());
            Path output = directory.resolve("source.mp4");
            Files.writeString(output, "0123456");

            java.io.ByteArrayOutputStream ffmpegInput = new java.io.ByteArrayOutputStream();
            service.download(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/video.mp4"), output,
                    ffmpegInput).block(java.time.Duration.ofSeconds(10));

            assertThat(Files.readAllBytes(output)).isEqualTo(content);
            assertThat(ffmpegInput.toByteArray()).isEqualTo(content);
            assertThat(requests).hasValue(2);
            assertThat(ranges).containsExactly("bytes=7-8388614", "bytes=7-8388614");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresRemuxOnlyForSingleProgressiveFormat() {
        assertThat(ProgressiveDashService.requiresRemux(video())).isTrue();
        UpstreamVideo.Format adaptive = new UpstreamVideo.Format("137", "https://rr1.googlevideo.com/video",
                "video/mp4", "avc1", 1920, 1080, 25.0, 500_000L, null, null,
                true, false, "0-740", "741-900");
        assertThat(ProgressiveDashService.requiresRemux(new UpstreamVideo("video", 100L,
                List.of(adaptive), List.of()))).isFalse();
    }

    private static UpstreamVideo video() {
        UpstreamVideo.Format format = new UpstreamVideo.Format("18", "https://rr1.googlevideo.com/video",
                "video/mp4", "avc1.4D401E,mp4a.40.2", 640, 360, 25.0, 493_969L, 44_100,
                24_079_964L, true, true, null, null);
        return new UpstreamVideo("MQziBPCMEL8", 390L, List.of(format), List.of());
    }

    private static final class TestService extends ProgressiveDashService {
        private static final String EARLY_MANIFEST = """
                <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
                  <Period><AdaptationSet><Representation id="0"><SegmentTemplate>
                    <SegmentTimeline><S t="0" d="12800"/></SegmentTimeline>
                  </SegmentTemplate></Representation></AdaptationSet>
                  <AdaptationSet><Representation id="1"><SegmentTemplate>
                    <SegmentTimeline><S t="0" d="44100"/></SegmentTimeline>
                  </SegmentTemplate></Representation></AdaptationSet></Period>
                </MPD>
                """;
        private final AtomicInteger downloads = new AtomicInteger();
        private final AtomicInteger remuxes = new AtomicInteger();
        private final CountDownLatch finishRemux = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        private TestService(UpstreamClient upstream, Path root) {
            super(null, upstream, mock(PlaybackLogService.class), root.toString(), "ffmpeg", new MediaRedirectCache());
        }

        @Override
        Mono<Path> downloadAndRemux(URI source, Path input, Path directory) {
            downloads.incrementAndGet();
            remuxes.incrementAndGet();
            return Mono.delay(java.time.Duration.ofMillis(150)).then(Mono.fromCallable(() -> {
                Files.writeString(input, "source");
                Files.createDirectories(directory.resolve("remux"));
                Files.writeString(directory.resolve("manifest.mpd"), EARLY_MANIFEST);
                for (String filename : List.of("init-0.m4s", "init-1.m4s", "chunk-0-00001.m4s", "chunk-1-00001.m4s")) {
                    Files.writeString(directory.resolve("remux").resolve(filename), "media");
                }
                Files.writeString(directory.resolve("remux/init-stream0.m4s"), "init");
                return directory;
            })).then(Mono.fromCallable(() -> {
                if (!finishRemux.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test remux was not released");
                }
                Files.writeString(directory.resolve("manifest.mpd"), "<MPD type=\"static\"/>");
                completed.countDown();
                return directory;
            })).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }
    }
}
