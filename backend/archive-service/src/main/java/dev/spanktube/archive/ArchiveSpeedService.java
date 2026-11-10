package dev.spanktube.archive;

import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
class ArchiveSpeedService {

    private static final long DEFAULT_BPS = 3 * 1024 * 1024L;

    private final LongAdder videoBytes = new LongAdder();
    private final LongAdder videoNanos = new LongAdder();
    private final LongAdder audioBytes = new LongAdder();
    private final LongAdder audioNanos = new LongAdder();

    void record(ArchiveModels.Stage stage, long bytes, long nanos) {
        if (bytes <= 0 || nanos <= 0) {
            return;
        }
        if (stage == ArchiveModels.Stage.DOWNLOADING_VIDEO) {
            videoBytes.add(bytes);
            videoNanos.add(nanos);
        } else if (stage == ArchiveModels.Stage.DOWNLOADING_AUDIO) {
            audioBytes.add(bytes);
            audioNanos.add(nanos);
        }
    }

    long videoBps() {
        return rate(videoBytes.sum(), videoNanos.sum());
    }

    long audioBps() {
        return rate(audioBytes.sum(), audioNanos.sum());
    }

    long estimateSeconds(long video, long audio) {
        return (long) Math.ceil(video / (double) videoBps() + audio / (double) audioBps());
    }

    private static long rate(long bytes, long nanos) {
        return nanos <= 0 ? DEFAULT_BPS : Math.max(1, Math.round(bytes * 1_000_000_000.0 / nanos));
    }
}
