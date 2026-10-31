package dev.spanktube.upstream;

public record StreamFormat(
        String formatId,
        String url,
        String mimeType,
        String codecs,
        Integer width,
        Integer height,
        Double fps,
        Long bitrate,
        Integer audioSampleRate,
        Long contentLength,
        boolean hasVideo,
        boolean hasAudio,
        String initRange,
        String indexRange) {
}
