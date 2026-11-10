package dev.spanktube.archive;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
class ArchiveChannelStatusService {

    private final ArchiveRepository repository;
    private final ArchivePreflightService preflights;
    private final UpstreamClient upstreamClient;
    private final CatalogClient catalogClient;

    ArchiveChannelStatusService(ArchiveRepository repository, ArchivePreflightService preflights,
            UpstreamClient upstreamClient, CatalogClient catalogClient) {
        this.repository = repository;
        this.preflights = preflights;
        this.upstreamClient = upstreamClient;
        this.catalogClient = catalogClient;
    }

    Mono<ChannelArchiveStatus> status(String channelId, String username) {
        return Mono.fromCallable(() -> compute(channelId, username)).subscribeOn(Schedulers.boundedElastic());
    }

    private ChannelArchiveStatus compute(String channelId, String username) {
        Instant lastArchivedAt = repository.lastChannelArchive(username, channelId);
        if (lastArchivedAt == null) {
            return new ChannelArchiveStatus(null, 0, 0);
        }
        var publishedAts = new ConcurrentHashMap<String, String>();
        List<String> videoIds = preflights.allVideoIds(ArchiveModels.Kind.CHANNEL, channelId, publishedAts);
        int newVideoCount = (int) videoIds.stream()
                .filter(videoId -> isAfter(publishedAts.get(videoId), lastArchivedAt)
                        && catalogClient.existing(username, videoId).isEmpty())
                .count();
        Set<String> archivedPlaylists = new HashSet<>(catalogClient.archivedPlaylistIds(username, channelId));
        int newPlaylistCount = (int) preflights.allPages(cursor -> upstreamClient.channelPlaylists(channelId, cursor))
                .stream().filter(playlistId -> !archivedPlaylists.contains(playlistId)).count();
        return new ChannelArchiveStatus(lastArchivedAt, newVideoCount, newPlaylistCount);
    }

    private static boolean isAfter(String value, Instant lastArchivedAt) {
        Instant publishedAt = PublicationDate.parse(value);
        return publishedAt != null && publishedAt.isAfter(lastArchivedAt);
    }

    record ChannelArchiveStatus(Instant lastArchivedAt, int newVideoCount, int newPlaylistCount) {
    }
}
