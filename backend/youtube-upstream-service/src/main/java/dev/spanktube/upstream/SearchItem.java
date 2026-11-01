package dev.spanktube.upstream;

public record SearchItem(
        String kind,
        String id,
        String title,
        String url,
        String thumbnailUrl,
        String channelName,
        Long durationSeconds,
        Integer videoCount) {
}
