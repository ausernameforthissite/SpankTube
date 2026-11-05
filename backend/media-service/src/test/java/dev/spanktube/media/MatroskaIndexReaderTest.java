package dev.spanktube.media;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MatroskaIndexReaderTest {

    @TempDir
    Path directory;

    @Test
    void readsCueTimesWithoutReadingMediaPayload() throws Exception {
        byte[] info = element(0x1549A966L, element(0x2AD7B1L, unsigned(1_000_000)));
        byte[] seekHead = element(0x114D9B74L, seek(0x1549A966L, 0), seek(0x1C53BB6BL, 0));
        int infoPosition = seekHead.length;
        int cuesPosition = seekHead.length + info.length;
        seekHead = element(0x114D9B74L, seek(0x1549A966L, infoPosition), seek(0x1C53BB6BL, cuesPosition));
        byte[] cues = element(0x1C53BB6BL, point(0, 1), point(133, 1), point(2000, 1));
        byte[] file = concat(element(0x1A45DFA3L, new byte[] {1, 2, 3}),
                element(0x18538067L, concat(seekHead, info, cues)));
        Path source = directory.resolve("index.mkv");
        Files.write(source, file);

        List<Double> keyframes = MatroskaIndexReader.keyframes(source);

        assertThat(keyframes).containsExactly(0.0, 0.133, 2.0);
        assertThat(Files.size(source)).isLessThan(1024);
    }

    private static byte[] seek(long id, long position) {
        return element(0x4DBBL, element(0x53ABL, idBytes(id)),
                element(0x53ACL, unsigned(position)));
    }

    private static byte[] point(long time, long track) {
        return element(0xBBL, element(0xB3L, unsigned(time)),
                element(0xB7L, element(0xF7L, unsigned(track)), element(0xF1L, unsigned(1234))));
    }

    private static byte[] element(long id, byte[]... children) {
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            writeId(result, id);
            byte[] payload = concat(children);
            writeSize(result, payload.length);
            result.write(payload);
            return result.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] unsigned(long value) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        boolean started = false;
        for (int shift = 56; shift >= 0; shift -= 8) {
            int current = (int) (value >>> shift) & 0xFF;
            if (current != 0 || started || shift == 0) {
                result.write(current);
                started = true;
            }
        }
        return result.toByteArray();
    }

    private static byte[] idBytes(long id) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeId(result, id);
        return result.toByteArray();
    }

    private static void writeId(ByteArrayOutputStream output, long id) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            int current = (int) (id >>> shift) & 0xFF;
            if (current != 0 || shift == 0 || output.size() > 0) {
                output.write(current);
            }
        }
    }

    private static void writeSize(ByteArrayOutputStream output, long size) {
        int length = 1;
        while (length < 8 && size >= (1L << (7 * length))) {
            length++;
        }
        long first = (size >>> (7 * (length - 1))) | (0x80 >>> (length - 1));
        output.write((int) first);
        for (int index = 1; index < length; index++) {
            output.write((int) (size >>> (7 * (length - index - 1))) & 0xFF);
        }
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] value : values) {
            result.write(value, 0, value.length);
        }
        return result.toByteArray();
    }
}
