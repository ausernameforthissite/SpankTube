package dev.spanktube.archive;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationDateTest {

    @Test
    void parsesYtDlpDates() {
        assertThat(PublicationDate.parse("20240203")).isEqualTo(Instant.parse("2024-02-03T00:00:00Z"));
        assertThat(PublicationDate.parse("2024-02-03T04:05:06+02:00"))
                .isEqualTo(Instant.parse("2024-02-03T02:05:06Z"));
        assertThat(PublicationDate.parse(null)).isNull();
        assertThat(PublicationDate.parse("unknown")).isNull();
    }
}
