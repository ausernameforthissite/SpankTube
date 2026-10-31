package dev.spanktube.upstream;

public record SubtitleTrack(
        String trackId,
        String language,
        String label,
        String format,
        String url) {
}
