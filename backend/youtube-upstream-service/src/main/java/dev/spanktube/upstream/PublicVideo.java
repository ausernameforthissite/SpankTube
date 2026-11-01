package dev.spanktube.upstream;

import java.util.List;

public record PublicVideo(
        String videoId,
        String title,
        String description,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String channelUrl,
        String thumbnailUrl,
        Long durationSeconds,
        Long viewCount,
        Long likeCount,
        String publishedAt,
        String manifestUrl,
        List<Format> formats,
        List<Subtitle> subtitles) {

    static PublicVideo from(VideoDetails details) {
        String prefix = "/media/v/" + details.videoId();
        List<Format> formats = details.formats().stream()
                .map(format -> new Format(format.formatId(), format.mimeType(), format.codecs(),
                        format.width(), format.height(), format.fps(), format.bitrate(),
                        format.hasVideo(), format.hasAudio(),
                        prefix + "/seg/" + format.formatId()))
                .toList();
        List<Subtitle> subtitles = details.subtitles().stream()
                .map(track -> new Subtitle(track.trackId(), track.language(), track.label(),
                        prefix + "/sub/" + track.trackId()))
                .toList();
        String channelUrl = details.channelCustomPath() != null ? "/c/" + details.channelCustomPath()
                : details.channelHandle() != null ? "/@" + details.channelHandle()
                : "/channel/" + details.channelId();
        return new PublicVideo(details.videoId(), details.title(), details.description(),
                details.channelId(), details.channelName(),
                details.channelAvatarUrl() == null ? null : "/media/channel/" + details.channelId() + "/avatar",
                channelUrl, prefix + "/thumbnail", details.durationSeconds(), details.viewCount(), details.likeCount(),
                details.publishedAt(), prefix + "/manifest.mpd", formats, subtitles);
    }

    public record Format(String formatId, String mimeType, String codecs, Integer width, Integer height,
            Double fps, Long bitrate, boolean hasVideo, boolean hasAudio, String segmentUrl) {
    }

    public record Subtitle(String trackId, String language, String label, String url) {
    }
}
