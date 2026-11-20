package dev.spanktube.archive;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

record ArchiveFilter(Mode mode, NumericCondition likes, NumericCondition views, DateCondition publishedAt) {

    ArchiveFilter {
        if (mode == null) {
            mode = Mode.AND;
        }
    }

    static ArchiveFilter all() {
        return new ArchiveFilter(Mode.AND, null, null, null);
    }

    boolean matches(UpstreamClient.VideoDownload video) {
        int active = 0;
        boolean result = mode == Mode.AND;
        if (likes != null) {
            active++;
            result = combine(result, likes.matches(video.likeCount()));
        }
        if (views != null) {
            active++;
            result = combine(result, views.matches(video.viewCount()));
        }
        if (publishedAt != null) {
            active++;
            result = combine(result, publishedAt.matches(video.metadata().publishedAt()));
        }
        return active == 0 || result;
    }

    private boolean combine(boolean previous, boolean current) {
        return mode == Mode.OR ? previous || current : previous && current;
    }

    enum Mode { AND, OR }

    record NumericCondition(Long exact, Long minimum, Long maximum,
            Boolean minimumInclusive, Boolean maximumInclusive) {
        NumericCondition {
            nonNegative(exact);
            nonNegative(minimum);
            nonNegative(maximum);
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new IllegalArgumentException("Minimum cannot exceed maximum");
            }
        }

        boolean matches(Long value) {
            if (value == null) return false;
            if (exact != null) return value.longValue() == exact.longValue();
            if (minimum != null) {
                int comparison = value.compareTo(minimum);
                if (comparison < 0 || comparison == 0 && Boolean.FALSE.equals(minimumInclusive)) return false;
            }
            if (maximum != null) {
                int comparison = value.compareTo(maximum);
                if (comparison > 0 || comparison == 0 && Boolean.FALSE.equals(maximumInclusive)) return false;
            }
            return true;
        }

        private static void nonNegative(Long value) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException("Likes and views cannot be negative");
            }
        }
    }

    record DateCondition(Instant exact, Instant minimum, Instant maximum,
            Boolean minimumInclusive, Boolean maximumInclusive) {
        DateCondition {
            if (minimum != null && maximum != null && minimum.isAfter(maximum)) {
                throw new IllegalArgumentException("Minimum date cannot exceed maximum date");
            }
        }

        boolean matches(String value) {
            if (value == null || value.isBlank()) return false;
            Instant published = Instant.parse(value);
            if (exact != null) {
                return published.truncatedTo(ChronoUnit.SECONDS).equals(exact.truncatedTo(ChronoUnit.SECONDS));
            }
            if (minimum != null) {
                int comparison = published.compareTo(minimum);
                if (comparison < 0 || comparison == 0 && Boolean.FALSE.equals(minimumInclusive)) return false;
            }
            if (maximum != null) {
                int comparison = published.compareTo(maximum);
                if (comparison > 0 || comparison == 0 && Boolean.FALSE.equals(maximumInclusive)) return false;
            }
            return true;
        }
    }
}
