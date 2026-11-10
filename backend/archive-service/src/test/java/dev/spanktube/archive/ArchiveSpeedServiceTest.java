package dev.spanktube.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveSpeedServiceTest {

    @Test
    void fallbackRateIsThreeMebibytesPerSecond() {
        ArchiveSpeedService service = new ArchiveSpeedService();

        assertThat(service.videoBps()).isEqualTo(3 * 1024 * 1024L);
        assertThat(service.audioBps()).isEqualTo(3 * 1024 * 1024L);
        assertThat(service.estimateSeconds(3 * 1024 * 1024L, 1024 * 1024L)).isEqualTo(2);
    }
}
