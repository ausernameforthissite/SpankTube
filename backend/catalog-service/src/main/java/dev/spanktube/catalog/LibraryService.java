package dev.spanktube.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LibraryService {

    private final CatalogRepository repository;
    private final UserQuotaClient quotaClient;
    private final Path archiveRoot;

    LibraryService(CatalogRepository repository, UserQuotaClient quotaClient,
            @Value("${spanktube.archive.root:/archive}") String archiveRoot) {
        this.repository = repository;
        this.quotaClient = quotaClient;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
    }

    LibraryModels.LibraryResponse library(String username, String channelId, String sort, String direction) {
        List<LibraryModels.LibraryChannel> channels = repository.channels(username).stream()
                .filter(channel -> channelId == null || channelId.isBlank() || channel.channelId().equals(channelId))
                .map(channel -> {
                    List<LibraryModels.LibraryItem> items = repository.channelVideos(username, channel.channelId()).stream()
                            .map(this::libraryItem)
                            .sorted(itemComparator(sort, direction))
                            .toList();
                    long total = items.stream().mapToLong(LibraryModels.LibraryItem::sizeBytes).sum();
                    return new LibraryModels.LibraryChannel(channel, items, total);
                })
                .toList();
        LibraryModels.QuotaResponse quota = quotaClient.resolve(username);
        return new LibraryModels.LibraryResponse(channels, repository.usage(username),
                quota.effectiveQuotaBytes(), quota.lruThresholdPercent());
    }

    long usage(String username) {
        return repository.usage(username);
    }

    @Transactional
    public void prepare(String username, long requiredBytes, String videoId) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("requiredBytes must not be negative");
        }
        if (repository.hasLink(username, videoId)) {
            return;
        }
        LibraryModels.QuotaResponse quota = quotaClient.resolve(username);
        if (requiredBytes > quota.effectiveQuotaBytes()) {
            throw new QuotaExceededException("The file is larger than the user's quota");
        }
        long threshold = quota.effectiveQuotaBytes() * quota.lruThresholdPercent() / 100;
        while (repository.usage(username) + requiredBytes > threshold) {
            String oldest = repository.oldestLink(username);
            if (oldest == null) {
                throw new QuotaExceededException("Not enough free space after LRU eviction");
            }
            deleteLinkAndFile(username, oldest);
        }
    }

    ArchiveCompletedEvent existing(String username, String videoId) {
        return repository.existing(username, videoId);
    }

    boolean hasLink(String username, String videoId) {
        return repository.hasLink(username, videoId);
    }

    @Transactional
    public void register(ArchiveCompletedEvent event) {
        repository.upsert(event);
    }

    @Transactional
    public boolean delete(String username, String videoId) {
        if (!repository.hasLink(username, videoId)) {
            return false;
        }
        deleteLinkAndFile(username, videoId);
        return true;
    }

    Path file(String username, String videoId) {
        if (!repository.hasLink(username, videoId)) {
            return null;
        }
        String path = repository.library(username).stream()
                .filter(video -> video.videoId().equals(videoId))
                .findFirst()
                .map(LibraryModels.Video::archivedPath)
                .orElse(null);
        return path == null ? null : safePath(path);
    }

    List<LibraryModels.StorageUser> storageUsers() {
        return repository.owners().stream()
                .map(username -> {
                    LibraryModels.QuotaResponse quota = quotaClient.resolve(username);
                    return new LibraryModels.StorageUser(username, quota.role(), repository.usage(username),
                            quota.effectiveQuotaBytes(), quota.lruThresholdPercent());
                })
                .toList();
    }

    private LibraryModels.LibraryItem libraryItem(LibraryModels.Video video) {
        return new LibraryModels.LibraryItem(video.videoId(), video.title(),
                video.durationSeconds() == null ? 0 : video.durationSeconds(), video.sizeBytes(),
                video.sizeBytes() / 1_000_000.0, "/api/v1/library/" + video.videoId() + "/file",
                video.lastAccessedAt());
    }

    private Comparator<LibraryModels.LibraryItem> itemComparator(String sort, String direction) {
        Comparator<LibraryModels.LibraryItem> comparator = switch (sort == null ? "size" : sort) {
            case "duration" -> Comparator.comparing(LibraryModels.LibraryItem::durationSeconds);
            case "title" -> Comparator.comparing(LibraryModels.LibraryItem::title, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(LibraryModels.LibraryItem::sizeBytes);
        };
        return "asc".equals(direction) ? comparator : comparator.reversed();
    }

    private void deleteLinkAndFile(String username, String videoId) {
        String orphanPath = repository.deleteLink(username, videoId);
        if (orphanPath != null) {
            deleteDirectory(safePath(orphanPath).getParent());
        }
    }

    private Path safePath(String path) {
        Path resolved = archiveRoot.resolve(path).normalize();
        if (!resolved.startsWith(archiveRoot)) {
            throw new IllegalArgumentException("Archive path escapes archive root");
        }
        return resolved;
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new IllegalStateException("Unable to delete " + path, error);
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException("Unable to clean archive directory " + directory, error);
        }
    }
}
