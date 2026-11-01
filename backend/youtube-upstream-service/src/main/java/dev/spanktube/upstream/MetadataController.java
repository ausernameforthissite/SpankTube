package dev.spanktube.upstream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class MetadataController {

    private final UpstreamProvider provider;

    MetadataController(UpstreamProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/api/v1/search")
    Mono<SearchResults> search(@RequestParam String q, @RequestParam(defaultValue = "videos") String type,
            @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> provider.search(q, type, cursor)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/v1/channels/{channelId}")
    Mono<PublicChannel> channel(@PathVariable String channelId,
            @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> {
                    ChannelDetails channel = provider.channel(channelId, cursor);
                    SearchResults playlists = provider.channelPlaylists(channelId, null);
                    return new ChannelDetails(channel.channelId(), channel.title(), channel.handle(), channel.customPath(),
                            channel.avatarUrl(), channel.subscriberCount(), channel.videos(), playlists.items(),
                            channel.nextCursor());
                })
                .map(PublicChannel::from)
                .subscribeOn(Schedulers.boundedElastic());
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
            @RequestParam(defaultValue = "0") String cursor) {
        return Mono.fromCallable(() -> provider.playlist(playlistId, cursor))
                .map(PublicPlaylist::from)
                .subscribeOn(Schedulers.boundedElastic());
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
