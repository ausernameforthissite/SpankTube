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

    private static void putBox(byte[] bytes, int offset, int size, String type) {
        ByteBuffer.wrap(bytes).putInt(offset, size);
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(typeBytes, 0, bytes, offset + 4, typeBytes.length);
    }
}
