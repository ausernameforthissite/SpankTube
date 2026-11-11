package dev.spanktube.media;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Mp4IndexServiceTest {

    @Test
    void readsInitializationAndIndexRangesFromSidx() {
        byte[] bytes = new byte[1600];
        putBox(bytes, 0, 28, "ftyp");
        putBox(bytes, 28, 707, "moov");
        putBox(bytes, 735, 788, "sidx");
        UpstreamVideo.Format format = new UpstreamVideo.Format("160", "https://rr1.googlevideo.com/video",
                "video/mp4", "avc1.4D400B", 256, 144, 25.0, 100_000L, null,
                889_068L, true, false, null, null);

        UpstreamVideo.Format indexed = Mp4IndexService.withIndex(format, bytes);

        assertThat(indexed.initRange()).isEqualTo("0-734");
        assertThat(indexed.indexRange()).isEqualTo("735-1522");
    }

    @Test
    void readsLargeHeaderAndActualAvcProfile() {
        byte[] bytes = new byte[256 * 1024];
        putBox(bytes, 0, 28, "ftyp");
        putBox(bytes, 28, 200_000, "moov");
        byte[] avcC = {1, 0x4d, 0x40, 0x1e};
        System.arraycopy("avcC".getBytes(StandardCharsets.ISO_8859_1), 0, bytes, 566, 4);
        System.arraycopy(avcC, 0, bytes, 570, avcC.length);
        putBox(bytes, 200_028, 788, "sidx");
        UpstreamVideo.Format format = new UpstreamVideo.Format("18", "https://rr1.googlevideo.com/video",
                "video/mp4", "avc1.42001E,mp4a.40.2", 640, 360, 25.0, 100_000L, 44_100,
                24_079_964L, true, true, null, null);

        UpstreamVideo.Format indexed = Mp4IndexService.withIndex(format, bytes);

        assertThat(indexed.initRange()).isEqualTo("0-200027");
        assertThat(indexed.indexRange()).isEqualTo("200028-200815");
        assertThat(indexed.codecs()).isEqualTo("avc1.4D401E,mp4a.40.2");
    }

    @Test
    void compactKeepsResolutionsDistinctAudioAndProgressiveMp4() {
        List<UpstreamVideo.Format> compact = Mp4IndexService.compactFormats(List.of(
                video("137-low", "video/mp4", 1080, 1000),
                video("137-high", "video/mp4", 1080, 2000),
                video("248", "video/webm", 1080, 9000),
                video("136", "video/mp4", 720, 1000),
                video("247", "video/webm", 720, 2000),
                progressive("18"),
                audio("251-ru-low", "ru", "Russian", true, false, 100),
                audio("251-ru-high", "ru", "Russian", true, false, 200),
                audio("251-en-auto", "en", "English auto", false, true, 100),
                audio("251-en-human", "en", "English", false, false, 100),
                audio("139-drc", "ru", "low, DRC", false, false, 100)));

        assertThat(compact).extracting(UpstreamVideo.Format::trackId)
                .containsExactly("137-high", "136", "18", "251-ru-high", "251-en-auto", "251-en-human", "139-drc");
    }

    @Test
    void cachesEnrichedIndexesAndKeepsFullModeSeparate() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "video/mp4")
                    .body(reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                            .wrap(sidx()))).build());
        }).build();
        Mp4IndexService service = new Mp4IndexService(client, mock(PlaybackLogService.class));
        UpstreamVideo video = new UpstreamVideo("dQw4w9WgXcQ", 100L, List.of(
                video("137-low", "video/mp4", 1080, 1000),
                video("137-high", "video/mp4", 1080, 2000),
                audio("251-ru", "ru", "Russian", true, false, 100)), List.of());

        UpstreamVideo compact = service.enrich(video, "first").block();
        UpstreamVideo cached = service.enrich(video, "second").block();
        UpstreamVideo full = service.enrich(video, "full", true).block();

        assertThat(compact).isNotNull();
        assertThat(cached).isNotNull();
        assertThat(full).isNotNull();
        assertThat(compact.formats()).hasSize(2);
        assertThat(cached.formats()).hasSize(2);
        assertThat(full.formats()).hasSize(3);
        assertThat(requests).hasValue(3);
    }

    private static byte[] sidx() {
        byte[] bytes = new byte[24];
        ByteBuffer.wrap(bytes).putInt(0, 24);
        System.arraycopy("sidx".getBytes(StandardCharsets.ISO_8859_1), 0, bytes, 4, 4);
        return bytes;
    }

    private static UpstreamVideo.Format video(String trackId, String mimeType, int height, long bitrate) {
        return new UpstreamVideo.Format(trackId, "https://rr1.googlevideo.com/video", mimeType,
                mimeType.equals("video/mp4") ? "avc1.640028" : "vp9", height * 16 / 9, height, 30.0,
                bitrate, null, null, true, false, null, null, trackId, null, null, null, false);
    }

    private static UpstreamVideo.Format audio(String trackId, String language, String label, boolean original,
            boolean automatic, long bitrate) {
        return new UpstreamVideo.Format(trackId, "https://rr1.googlevideo.com/audio", "audio/webm", "opus",
                null, null, null, bitrate, 48_000, null, false, true, null, null,
                trackId, language, label, original, automatic);
    }

    private static UpstreamVideo.Format progressive(String trackId) {
        return new UpstreamVideo.Format(trackId, "https://rr1.googlevideo.com/video", "video/mp4",
                "avc1.4D401E,mp4a.40.2", 640, 360, 25.0, 500_000L, 44_100,
                null, true, true, null, null, trackId, null, null, null, false);
    }

    private static void putBox(byte[] bytes, int offset, int size, String type) {
        ByteBuffer.wrap(bytes).putInt(offset, size);
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(typeBytes, 0, bytes, offset + 4, typeBytes.length);
    }
}
