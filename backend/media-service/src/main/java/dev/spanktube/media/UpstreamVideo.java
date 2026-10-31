package dev.spanktube.media;

import java.util.List;

public record UpstreamVideo(String videoId, Long durationSeconds,
        List<Format> formats, List<Subtitle> subtitles) {

    public record Format(String formatId, String url, String mimeType, String codecs,
            Integer width, Integer height, Double fps, Long bitrate, Integer audioSampleRate,
            Long contentLength, boolean hasVideo, boolean hasAudio, String initRange, String indexRange) {
    }

    public record Subtitle(String trackId, String language, String label, String format, String url) {
    }
}
