package dev.spanktube.media;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MatroskaIndexReader {

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long SEEK = 0x4DBBL;
    private static final long SEEK_ID = 0x53ABL;
    private static final long SEEK_POSITION = 0x53ACL;
    private static final long INFO = 0x1549A966L;
    private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
    private static final long CUES = 0x1C53BB6BL;
    private static final long CUE_POINT = 0xBBL;
    private static final long CUE_TIME = 0xB3L;
    private static final long CUE_TRACK_POSITIONS = 0xB7L;
    private static final long CUE_TRACK = 0xF7L;

    private MatroskaIndexReader() {
    }

    static List<Double> keyframes(Path file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            expect(input, EBML);
            expect(input, SEGMENT);
            long segmentOffset = input.getFilePointer();
            long seekHeadOffset = expect(input, SEEK_HEAD);
            byte[] seekHead = read(input, seekHeadOffset);
            Map<Long, Long> targets = seekTargets(seekHead);
            Long cuesPosition = targets.get(CUES);
            if (cuesPosition == null) {
                throw new IOException("Matroska Cues are missing");
            }
            long scale = timestampScale(input, segmentOffset, targets);
            input.seek(segmentOffset + cuesPosition);
            byte[] cues = read(input, expect(input, CUES));
            List<Double> result = new ArrayList<>();
            for (Element point : elements(cues)) {
                if (point.id != CUE_POINT) {
                    continue;
                }
                Long timestamp = null;
                Long track = null;
                for (Element child : elements(point.payload)) {
                    if (child.id == CUE_TIME) {
                        timestamp = unsigned(child.payload);
                    } else if (child.id == CUE_TRACK_POSITIONS) {
                        for (Element position : elements(child.payload)) {
                            if (position.id == CUE_TRACK) {
                                track = unsigned(position.payload);
                                if (track == 1) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (timestamp != null && Long.valueOf(1).equals(track)) {
                    double seconds = timestamp * scale / 1_000_000_000.0;
                    if (result.isEmpty() || seconds > result.getLast()) {
                        result.add(seconds);
                    }
                }
            }
            if (result.isEmpty()) {
                throw new IOException("Matroska Cues contain no video keyframes");
            }
            return List.copyOf(result);
        }
    }

    private static long timestampScale(RandomAccessFile input, long segmentOffset, Map<Long, Long> targets)
            throws IOException {
        Long position = targets.get(INFO);
        if (position == null) {
            return 1_000_000L;
        }
        input.seek(segmentOffset + position);
        byte[] info = read(input, expect(input, INFO));
        for (Element child : elements(info)) {
            if (child.id == TIMESTAMP_SCALE) {
                return unsigned(child.payload);
            }
        }
        return 1_000_000L;
    }

    private static Map<Long, Long> seekTargets(byte[] data) throws IOException {
        Map<Long, Long> result = new HashMap<>();
        for (Element seek : elements(data)) {
            if (seek.id != SEEK) {
                continue;
            }
            Long id = null;
            Long position = null;
            for (Element child : elements(seek.payload)) {
                if (child.id == SEEK_ID) {
                    id = unsigned(child.payload);
                } else if (child.id == SEEK_POSITION) {
                    position = unsigned(child.payload);
                }
            }
            if (id != null && position != null) {
                result.put(id, position);
            }
        }
        return result;
    }

    private static long expect(RandomAccessFile input, long expected) throws IOException {
        long id = id(input);
        if (id != expected) {
            throw new IOException(String.format("Unexpected Matroska element %x instead of %x", id, expected));
        }
        long size = size(input);
        long position = input.getFilePointer();
        if (expected == EBML) {
            input.seek(position + size);
        } else if (expected != SEGMENT && (size < 0 || position + size > input.length())) {
            throw new EOFException("Truncated Matroska element");
        }
        return size;
    }

    private static byte[] read(RandomAccessFile input, long size) throws IOException {
        if (size < 0 || size > 64 * 1024 * 1024) {
            throw new IOException("Matroska metadata is too large");
        }
        byte[] result = new byte[(int) size];
        input.readFully(result);
        return result;
    }

    private static List<Element> elements(byte[] data) throws IOException {
        List<Element> result = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            while (input.available() > 0) {
                long id = id(input);
                long size = size(input);
                if (size < 0 || size > input.available()) {
                    throw new EOFException("Truncated Matroska child element");
                }
                byte[] payload = new byte[(int) size];
                input.readFully(payload);
                result.add(new Element(id, payload));
            }
        }
        return result;
    }

    private static long id(DataInput input) throws IOException {
        int first = input.readUnsignedByte();
        int length = length(first, 4);
        long value = first;
        for (int index = 1; index < length; index++) {
            value = (value << 8) | input.readUnsignedByte();
        }
        return value;
    }

    private static long size(DataInput input) throws IOException {
        int first = input.readUnsignedByte();
        int length = length(first, 8);
        long value = first & (0xFF >>> length);
        for (int index = 1; index < length; index++) {
            value = (value << 8) | input.readUnsignedByte();
        }
        return value;
    }

    private static int length(int first, int maximum) throws IOException {
        for (int length = 1; length <= maximum; length++) {
            if ((first & (0x80 >>> (length - 1))) != 0) {
                return length;
            }
        }
        throw new IOException("Invalid EBML varint");
    }

    private static long unsigned(byte[] data) {
        long result = 0;
        for (byte value : data) {
            result = (result << 8) | (value & 0xFF);
        }
        return result;
    }

    private record Element(long id, byte[] payload) {
    }
}
