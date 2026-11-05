package dev.spanktube.media;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static void putBox(byte[] bytes, int offset, int size, String type) {
        ByteBuffer.wrap(bytes).putInt(offset, size);
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(typeBytes, 0, bytes, offset + 4, typeBytes.length);
    }
}
