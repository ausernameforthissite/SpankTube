package dev.spanktube.archive;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveFilterTest {

    @Test
    void supportsIndependentInclusiveAndExclusiveEndpoints() {
        ArchiveFilter.NumericCondition range = new ArchiveFilter.NumericCondition(null, 10L, 20L, false, true);

        assertThat(range.matches(10L)).isFalse();
        assertThat(range.matches(11L)).isTrue();
        assertThat(range.matches(20L)).isTrue();
        assertThat(range.matches(21L)).isFalse();

        range = new ArchiveFilter.NumericCondition(null, 10L, 20L, true, false);
        assertThat(range.matches(10L)).isTrue();
        assertThat(range.matches(20L)).isFalse();
    }

    @Test
    void supportsUnboundedAndExactNumericConditions() {
        assertThat(new ArchiveFilter.NumericCondition(null, null, 100L, null, null).matches(100L)).isTrue();
        assertThat(new ArchiveFilter.NumericCondition(null, 100L, null, null, null).matches(100L)).isTrue();
        assertThat(new ArchiveFilter.NumericCondition(100L, null, null, null, null).matches(100L)).isTrue();
        assertThat(new ArchiveFilter.NumericCondition(100L, null, null, null, null).matches(101L)).isFalse();
        assertThat(new ArchiveFilter.NumericCondition(null, 100L, null, null, null).matches(null)).isFalse();
    }

    @Test
    void combinesActiveConditionsWithAndOr() {
        UpstreamClient.VideoDownload video = video(100L, 500L, "2026-01-02T03:04:05Z");
        ArchiveFilter.NumericCondition likes = new ArchiveFilter.NumericCondition(100L, null, null, null, null);
        ArchiveFilter.NumericCondition views = new ArchiveFilter.NumericCondition(500L, null, null, null, null);
        ArchiveFilter.DateCondition date = new ArchiveFilter.DateCondition(null,
                Instant.parse("2026-01-02T03:04:05Z"), null, true, null);

        assertThat(video.likeCount()).isEqualTo(100L);
        assertThat(video.viewCount()).isEqualTo(500L);
        assertThat(video.metadata().publishedAt()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(likes.matches(video.likeCount())).isTrue();
        assertThat(views.matches(video.viewCount())).isTrue();
        assertThat(date.matches(video.metadata().publishedAt())).isTrue();
        assertThat(new ArchiveFilter(ArchiveFilter.Mode.AND, likes, views, date).matches(video)).isTrue();
        assertThat(new ArchiveFilter(ArchiveFilter.Mode.AND, likes,
                new ArchiveFilter.NumericCondition(501L, null, null, null, null), null).matches(video)).isFalse();
        assertThat(new ArchiveFilter(ArchiveFilter.Mode.OR, null,
                new ArchiveFilter.NumericCondition(501L, null, null, null, null), date).matches(video)).isTrue();
        assertThat(new ArchiveFilter(ArchiveFilter.Mode.OR,
                new ArchiveFilter.NumericCondition(101L, null, null, null, null),
                new ArchiveFilter.NumericCondition(501L, null, null, null, null), null).matches(video)).isFalse();
        assertThat(ArchiveFilter.all().matches(video)).isTrue();
    }

    @Test
    void matchesExactDateToPickerSecondsAndChecksRangeEndpoints() {
        ArchiveFilter.DateCondition exact = new ArchiveFilter.DateCondition(
                Instant.parse("2026-01-02T03:04:05Z"), null, null, null, null);
        assertThat(exact.matches("2026-01-02T03:04:05.999Z")).isTrue();
        assertThat(exact.matches("2026-01-02T03:04:06Z")).isFalse();

        ArchiveFilter.DateCondition range = new ArchiveFilter.DateCondition(null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), false, false);
        assertThat(range.matches("2026-01-01T00:00:00Z")).isFalse();
        assertThat(range.matches("2026-01-02T00:00:00Z")).isFalse();
        assertThat(range.matches("2026-01-01T12:00:00Z")).isTrue();
        assertThat(range.matches(null)).isFalse();
    }

    @Test
    void rejectsInvalidNumericAndDateRanges() {
        assertThatThrownBy(() -> new ArchiveFilter.NumericCondition(null, 2L, 1L, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveFilter.NumericCondition(-1L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveFilter.DateCondition(null,
                Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UpstreamClient.VideoDownload video(long likes, long views, String publishedAt) {
        ArchiveModels.ExistingVideo metadata = mock(ArchiveModels.ExistingVideo.class);
        when(metadata.publishedAt()).thenReturn(publishedAt);
        return new UpstreamClient.VideoDownload(metadata, 0, List.of(), List.of(), List.of(), likes, views);
    }
}
