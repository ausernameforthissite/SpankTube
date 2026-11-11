package dev.spanktube.upstream;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class MetadataController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final UpstreamProvider provider;
    private final MetadataLogService logs;
    private final MetadataCacheService cache;

    MetadataController(UpstreamProvider provider, MetadataLogService logs, MetadataCacheService cache) {
        this.provider = provider;
        this.logs = logs;
        this.cache = cache;
    }

    @GetMapping(value = "/api/v1/metadata/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> metadataLogs(@RequestParam String session) {
        return logs.stream(session);
    }

    @GetMapping("/api/v1/search")
    Mono<SearchResults> search(@RequestParam String q, @RequestParam(defaultValue = "videos") String type,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Поиск метаданных…");
        return cached("search:" + type, q + ":" + page(cursor), SearchResults.class,
                () -> provider.search(q, type, cursor))
                .doOnNext(results -> logs.log(logSession, "Найдено элементов: " + results.items().size()))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/api/v1/channels/{channelId}")
    Mono<PublicChannel> channel(@PathVariable String channelId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String logSession,
            @RequestParam(defaultValue = "false") boolean includePlaylists) {
        logs.log(logSession, "Получение видео и плейлистов канала…");
        Mono<ChannelDetails> channel = cached("channel", channelId + ":" + page(cursor), ChannelDetails.class,
                () -> provider.channel(channelId, cursor))
                .doOnNext(details -> logs.log(logSession, "Видео канала получено: " + details.videos().size()));
        Mono<SearchResults> playlists = includePlaylists
                ? cached("channel-playlists", channelId + ":first", SearchResults.class,
                        () -> provider.channelPlaylists(channelId, null))
                        .doOnNext(results -> logs.log(logSession, "Плейлисты канала получены: " + results.items().size()))
                : Mono.just(new SearchResults(List.of(), null));
        return Mono.zip(channel, playlists)
                .map(result -> {
                    ChannelDetails details = result.getT1();
                    return PublicChannel.from(new ChannelDetails(details.channelId(), details.title(), details.handle(),
                            details.customPath(), details.avatarUrl(), details.subscriberCount(), details.videos(),
                            result.getT2().items(), details.nextCursor()));
                })
                .doOnNext(result -> logs.log(logSession, "Метаданные канала готовы"))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/api/v1/channels/{channelId}/videos")
    Mono<SearchResults> channelVideos(@PathVariable String channelId,
            @RequestParam(defaultValue = "0") String cursor) {
        return cached("channel-videos", channelId + ":" + page(cursor), SearchResults.class,
                () -> provider.channelVideos(channelId, cursor));
    }

    @GetMapping("/api/v1/channels/{channelId}/streams")
    Mono<SearchResults> channelLiveStreams(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return cached("channel-streams", channelId + ":" + page(cursor), SearchResults.class,
                () -> provider.channelLiveStreams(channelId, cursor));
    }

    @GetMapping("/api/v1/channels/{channelId}/playlists")
    Mono<SearchResults> channelPlaylists(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return cached("channel-playlists", channelId + ":" + page(cursor), SearchResults.class,
                () -> provider.channelPlaylists(channelId, cursor));
    }

    @GetMapping("/api/v1/playlists/{playlistId}")
    Mono<PublicPlaylist> playlist(@PathVariable String playlistId,
            @RequestParam(defaultValue = "0") String cursor, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Получение плейлиста и его видео…");
        return cached("playlist", playlistId + ":" + page(cursor), PlaylistDetails.class,
                () -> provider.playlist(playlistId, cursor))
                .map(PublicPlaylist::from)
                .doOnNext(result -> logs.log(logSession, "Видео в плейлисте получено: " + result.items().size()))
                .doOnNext(result -> logs.log(logSession, "Метаданные плейлиста готовы"))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/internal/v1/channels/{channelId}")
    Mono<ChannelDetails> internalChannel(@PathVariable String channelId) {
        return cached("channel", channelId + ":first", ChannelDetails.class, () -> provider.channel(channelId, "0"));
    }

    @GetMapping("/internal/v1/channels/{channelId}/videos")
    Mono<SearchResults> internalChannelVideos(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return cached("channel-videos", channelId + ":" + page(cursor), SearchResults.class,
                () -> provider.channelVideos(channelId, cursor));
    }

    @GetMapping("/internal/v1/channels/{channelId}/streams")
    Mono<SearchResults> internalChannelLiveStreams(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return cached("channel-streams", channelId + ":" + page(cursor), SearchResults.class,
                () -> provider.channelLiveStreams(channelId, cursor));
    }

    @GetMapping("/internal/v1/playlists/{playlistId}")
    Mono<PlaylistDetails> internalPlaylist(@PathVariable String playlistId) {
        return cached("playlist", playlistId + ":first", PlaylistDetails.class, () -> provider.playlist(playlistId, "0"));
    }

    @GetMapping("/internal/v1/playlists/{playlistId}/videos")
    Mono<SearchResults> internalPlaylistVideos(@PathVariable String playlistId,
            @RequestParam(required = false) String cursor) {
        return cached("playlist", playlistId + ":" + page(cursor), PlaylistDetails.class,
                () -> provider.playlist(playlistId, cursor))
                .map(playlist -> new SearchResults(playlist.items(), playlist.nextCursor()));
    }

    private <T> Mono<T> cached(String namespace, String id, Class<T> type, Supplier<T> loader) {
        return cache.get(namespace, id, type, loader, CACHE_TTL);
    }

    private static String page(String cursor) {
        return cursor == null || cursor.isBlank() || "0".equals(cursor) ? "first" : cursor;
    }
}
