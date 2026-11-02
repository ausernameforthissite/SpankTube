package dev.spanktube.upstream;

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

    private final UpstreamProvider provider;
    private final MetadataLogService logs;

    MetadataController(UpstreamProvider provider, MetadataLogService logs) {
        this.provider = provider;
        this.logs = logs;
    }

    @GetMapping(value = "/api/v1/metadata/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> metadataLogs(@RequestParam String session) {
        return logs.stream(session);
    }

    @GetMapping("/api/v1/search")
    Mono<SearchResults> search(@RequestParam String q, @RequestParam(defaultValue = "videos") String type,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Поиск метаданных…");
        return Mono.fromCallable(() -> provider.search(q, type, cursor)).subscribeOn(Schedulers.boundedElastic())
                .doOnNext(results -> logs.log(logSession, "Найдено элементов: " + results.items().size()))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/api/v1/channels/{channelId}")
    Mono<PublicChannel> channel(@PathVariable String channelId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Получение видео и плейлистов канала…");
        Mono<ChannelDetails> channel = Mono.fromCallable(() -> provider.channel(channelId, cursor))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(details -> logs.log(logSession, "Видео канала получено: " + details.videos().size()));
        Mono<SearchResults> playlists = Mono.fromCallable(() -> provider.channelPlaylists(channelId, null))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(results -> logs.log(logSession, "Плейлисты канала получены: " + results.items().size()));
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
        return Mono.fromCallable(() -> provider.channelVideos(channelId, cursor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/v1/channels/{channelId}/playlists")
    Mono<SearchResults> channelPlaylists(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> provider.channelPlaylists(channelId, cursor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/v1/playlists/{playlistId}")
    Mono<PublicPlaylist> playlist(@PathVariable String playlistId,
            @RequestParam(defaultValue = "0") String cursor, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Получение плейлиста и его видео…");
        return Mono.fromCallable(() -> provider.playlist(playlistId, cursor))
                .map(PublicPlaylist::from)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(result -> logs.log(logSession, "Видео в плейлисте получено: " + result.items().size()))
                .doOnNext(result -> logs.log(logSession, "Метаданные плейлиста готовы"))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/internal/v1/channels/{channelId}")
    Mono<ChannelDetails> internalChannel(@PathVariable String channelId) {
        return Mono.fromCallable(() -> provider.channel(channelId, "0")).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/internal/v1/channels/{channelId}/videos")
    Mono<SearchResults> internalChannelVideos(@PathVariable String channelId) {
        return Mono.fromCallable(() -> provider.channelVideos(channelId, null)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/internal/v1/playlists/{playlistId}")
    Mono<PlaylistDetails> internalPlaylist(@PathVariable String playlistId) {
        return Mono.fromCallable(() -> provider.playlist(playlistId, "0")).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/internal/v1/playlists/{playlistId}/videos")
    Mono<SearchResults> internalPlaylistVideos(@PathVariable String playlistId) {
        return Mono.fromCallable(() -> provider.playlist(playlistId, null))
                .map(playlist -> new SearchResults(playlist.items(), null))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
