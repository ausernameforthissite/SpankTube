package dev.spanktube.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarImageTest {

    @Test
    void acceptsWebpUpTo256Pixels() throws Exception {
        byte[] data = webp(256, 256);

        AvatarImage.ImageInfo image = AvatarImage.read(data);

        assertThat(image.contentType()).isEqualTo("image/webp");
        assertThat(image.width()).isEqualTo(256);
        assertThat(image.height()).isEqualTo(256);
    }

    @Test
    void rejectsOversizedDimensionsAndBody() {
        assertThatThrownBy(() -> AvatarImage.read(webp(257, 256))).hasMessageContaining("256x256");
        assertThatThrownBy(() -> AvatarImage.read(new byte[200 * 1024 + 1])).hasMessageContaining("200 KiB");
    }

    private static byte[] webp(int width, int height) {
        byte[] data = new byte[30];
        System.arraycopy("RIFF".getBytes(), 0, data, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, data, 8, 4);
        System.arraycopy("VP8X".getBytes(), 0, data, 12, 4);
        write24(data, 24, width - 1);
        write24(data, 27, height - 1);
        return data;
    }

    private static void write24(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
    }
}
