package dev.spanktube.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AvatarImage {

    private AvatarImage() {
    }

    static ImageInfo read(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > 200 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be at most 200 KiB");
        }
        if (has(data, "RIFF") && hasAt(data, 8, "WEBP")) {
            return webp(data);
        }
        var image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() > 256 || image.getHeight() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be PNG, JPEG or WEBP up to 256x256");
        }
        String type = has(data, "\u0089PNG") ? "image/png" : "image/jpeg";
        return new ImageInfo(type, image.getWidth(), image.getHeight());
    }

    private static ImageInfo webp(byte[] data) {
        if (hasAt(data, 12, "VP8X") && data.length >= 30) {
            int width = unsigned24(data, 24) + 1;
            int height = unsigned24(data, 27) + 1;
            return checked("image/webp", width, height);
        }
        if (hasAt(data, 12, "VP8 ") && data.length >= 30
                && (data[23] & 0xff) == 0x9d && (data[24] & 0xff) == 0x01 && (data[25] & 0xff) == 0x2a) {
            int width = little16(data, 26) & 0x3fff;
            int height = little16(data, 28) & 0x3fff;
            return checked("image/webp", width, height);
        }
        if (hasAt(data, 12, "VP8L") && data.length >= 25 && (data[20] & 0xff) == 0x2f) {
            int bits = little32(data, 21);
            int width = (bits & 0x3fff) + 1;
            int height = ((bits >>> 14) & 0x3fff) + 1;
            return checked("image/webp", width, height);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WEBP avatar");
    }

    private static ImageInfo checked(String contentType, int width, int height) {
        if (width <= 0 || height <= 0 || width > 256 || height > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be at most 256x256");
        }
        return new ImageInfo(contentType, width, height);
    }

    private static boolean has(byte[] data, String value) {
        return hasAt(data, 0, value);
    }

    private static boolean hasAt(byte[] data, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.ISO_8859_1);
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (data[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned24(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) | ((data[offset + 2] & 0xff) << 16);
    }

    private static int little16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int little32(byte[] data, int offset) {
        return unsigned24(data, offset) | ((data[offset + 3] & 0xff) << 24);
    }

    record ImageInfo(String contentType, int width, int height) {
    }
}
