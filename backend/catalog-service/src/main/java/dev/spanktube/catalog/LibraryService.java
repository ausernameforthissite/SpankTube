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
    private final String ffprobeExecutable;
    private final java.util.concurrent.ConcurrentHashMap<ProbeKey, Long> durations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<ProbeKey, Long> audioBitrates =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final tools.jackson.databind.ObjectMapper objectMapper = new tools.jackson.databind.ObjectMapper();

    LibraryService(CatalogRepository repository, UserQuotaClient quotaClient, String archiveRoot) {
        this(repository, quotaClient, archiveRoot, "ffprobe");
    }

    @org.springframework.beans.factory.annotation.Autowired
    LibraryService(CatalogRepository repository, UserQuotaClient quotaClient,
            @Value("${spanktube.archive.root:/archive}") String archiveRoot,
            @Value("${spanktube.archive.ffprobe-executable:ffprobe}") String ffprobeExecutable) {
        this.repository = repository;
        this.quotaClient = quotaClient;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.ffprobeExecutable = ffprobeExecutable;
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
        prepare(username, requiredBytes, videoId, false);
    }

    @Transactional
    public void prepare(String username, long requiredBytes, String videoId, boolean additionalVersion) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("requiredBytes must not be negative");
        }
        if (repository.hasLink(username, videoId) && !additionalVersion) {
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
                throw new QuotaExceededException("Not enough free space after LRU eviction; archived versions are preserved");
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

    LibraryModels.FileDownload file(String username, String videoId) {
        ArchiveCompletedEvent video = repository.existing(username, videoId);
        return video == null ? null : new LibraryModels.FileDownload(safePath(video.archivedPath()), filename(video));
    }

    LibraryModels.ArchiveAvailability archiveAvailability(String username, String videoId) {
        CatalogRepository.VersionedVideo current = repository.currentVersion(username, videoId);
        if (current == null || !Files.isRegularFile(safePath(current.video().archivedPath()))) {
            return new LibraryModels.ArchiveAvailability(false, null, null, null);
        }
        return new LibraryModels.ArchiveAvailability(true, "/api/v1/library/" + videoId + "/file",
                filename(current.video()), archiveVersion(current));
    }

    List<LibraryModels.ArchiveVersion> archiveVersions(String username, String videoId) {
        return repository.versions(username, videoId).stream().map(this::archiveVersion).toList();
    }

    ArchiveCompletedEvent version(String username, String videoId, String versionId) {
        CatalogRepository.VersionedVideo version = repository.version(username, videoId, versionId);
        if (version == null) {
            return null;
        }
        ArchiveCompletedEvent video = version.video();
        return new ArchiveCompletedEvent(video.ownerUsername(), video.channelId(), video.channelName(),
                video.channelHandle(), video.channelCustomPath(), video.channelAvatarUrl(), video.channelSubscriberCount(),
                video.videoId(), video.title(), video.publishedAt(), video.durationSeconds(), video.archivedPath(),
                video.quality(), video.sizeBytes(), video.width(), video.height(), video.videoCodec(),
                video.audioCodec(), description(video), audioBitrate(video.archivedPath(), video.audioBitrate()));
    }

    LibraryModels.FileDownload fileVersion(String username, String videoId, String versionId) {
        CatalogRepository.VersionedVideo version = repository.version(username, videoId, versionId);
        return version == null ? null
                : new LibraryModels.FileDownload(safePath(version.video().archivedPath()), filename(version.video()));
    }

    private LibraryModels.ArchiveVersion archiveVersion(CatalogRepository.VersionedVideo version) {
        ArchiveCompletedEvent video = version.video();
        String channelUrl = video.channelCustomPath() != null ? "/c/" + video.channelCustomPath()
                : video.channelHandle() != null ? "/@" + video.channelHandle() : "/channel/" + video.channelId();
        String downloadUrl = "/api/v1/videos/" + video.videoId() + "/archive/versions/" + version.versionId() + "/file";
        return new LibraryModels.ArchiveVersion(version.versionId(), video.videoId(), video.title(), description(video),
                video.publishedAt(), actualDuration(video), video.sizeBytes(), video.width(), video.height(),
                video.videoCodec(), video.audioCodec(), audioBitrate(video.archivedPath(), video.audioBitrate()),
                video.channelId(), video.channelName(), channelUrl,
                "/media/channel/" + video.channelId() + "/avatar", downloadUrl, filename(video),
                version.supersededAt() == null);
    }

    private Long actualDuration(ArchiveCompletedEvent video) {
        try {
            Path file = safePath(video.archivedPath());
            ProbeKey key = new ProbeKey(Files.getLastModifiedTime(file), Files.size(file));
            Long cached = durations.get(key);
            if (cached != null) {
                return cached;
            }
            Process process = new ProcessBuilder(ffprobeExecutable, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString()).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var input = process.getInputStream()) {
                    return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException error) {
                    return error.getMessage();
                }
            });
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffprobe failed: " + output.get(1, java.util.concurrent.TimeUnit.SECONDS));
            }
            long seconds = Math.round(Double.parseDouble(output.get(1, java.util.concurrent.TimeUnit.SECONDS).trim()));
            durations.put(key, seconds);
            return seconds;
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(LibraryService.class)
                    .warn("Unable to measure archived duration for {}", video.videoId(), error);
            return video.durationSeconds();
        }
    }

    private record ProbeKey(java.nio.file.attribute.FileTime modifiedAt, long size) {
    }

    private String description(ArchiveCompletedEvent video) {
        if (video.description() != null && !video.description().isBlank()) {
            return video.description();
        }
        Path infoJson = safePath(video.archivedPath()).getParent().resolve("info.json");
        if (!Files.isRegularFile(infoJson)) {
            return video.description();
        }
        try {
            return objectMapper.readTree(Files.readString(infoJson)).path("description").asText(null);
        } catch (IOException | RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(LibraryService.class)
                    .warn("Unable to read archived metadata {}", infoJson, error);
            return video.description();
        }
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
                video.lastAccessedAt(), "/media/v/" + video.videoId() + "/thumbnail",
                video.width(), video.height(), video.videoCodec(), video.audioCodec(),
                audioBitrate(video.archivedPath(), video.audioBitrate()), filename(video));
    }

    private Long audioBitrate(String archivedPath, Long knownBitrate) {
        if (knownBitrate != null && knownBitrate > 0) {
            return knownBitrate;
        }
        try {
            Path file = safePath(archivedPath);
            ProbeKey key = new ProbeKey(Files.getLastModifiedTime(file), Files.size(file));
            Long cached = audioBitrates.get(key);
            if (cached != null) {
                return cached == 0 ? null : cached;
            }
            Process process = new ProcessBuilder(ffprobeExecutable, "-v", "error", "-show_entries",
                    "stream=codec_type,bit_rate", "-of", "json", file.toString()).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var input = process.getInputStream()) {
                    return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException error) {
                    return error.getMessage();
                }
            });
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffprobe failed: " + output.get(1, java.util.concurrent.TimeUnit.SECONDS));
            }
            long bitrate = 0;
            for (var stream : objectMapper.readTree(output.get(1, java.util.concurrent.TimeUnit.SECONDS)).path("streams")) {
                if ("audio".equals(stream.path("codec_type").asText()) && stream.path("bit_rate").isNumber()) {
                    bitrate = stream.path("bit_rate").asLong();
                    break;
                }
            }
            Path infoJson = file.getParent().resolve("info.json");
            if (bitrate <= 0 && Files.isRegularFile(infoJson)) {
                double abr = objectMapper.readTree(Files.readString(infoJson)).path("abr").asDouble(0);
                if (abr > 0) {
                    bitrate = Math.round(abr * 1000);
                }
            }
            audioBitrates.put(key, bitrate);
            return bitrate == 0 ? null : bitrate;
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(LibraryService.class)
                    .warn("Unable to measure audio bitrate for {}", archivedPath, error);
            return knownBitrate;
        }
    }

    private String filename(LibraryModels.Video video) {
        return filename(video.title(), video.videoId(), video.height(), video.videoCodec(),
                audioBitrate(video.archivedPath(), video.audioBitrate()), video.audioCodec());
    }

    private String filename(ArchiveCompletedEvent video) {
        return filename(video.title(), video.videoId(), video.height(), video.videoCodec(),
                audioBitrate(video.archivedPath(), video.audioBitrate()), video.audioCodec());
    }

    private static String filename(String title, String videoId, Integer height, String videoCodec,
            Long audioBitrate, String audioCodec) {
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isBlank()) {
            safeTitle = videoId;
        }
        if (safeTitle.length() > 160) {
            safeTitle = safeTitle.substring(0, 160);
        }
        String resolution = videoCodec == null ? "audio" : height == null ? "unknown" : height + "p";
        String bitrate = audioBitrate == null || audioBitrate <= 0 ? "unknown"
                : Math.max(1, Math.round(audioBitrate / 1000.0)) + "kbps";
        return safeTitle + " [" + videoId + "] (" + resolution + ", "
                + (videoCodec == null ? "none" : videoCodec) + ", " + bitrate + ", "
                + (audioCodec == null ? "none" : audioCodec) + ").mkv";
    }

    private Comparator<LibraryModels.LibraryItem> itemComparator(String sort, String direction) {
        Comparator<LibraryModels.LibraryItem> comparator = switch (sort == null ? "size" : sort) {
            case "duration" -> Comparator.comparing(LibraryModels.LibraryItem::durationSeconds);
            case "title" -> Comparator.comparing(LibraryModels.LibraryItem::title, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(LibraryModels.LibraryItem::sizeBytes);
        };
        return "asc".equals(direction) ? comparator : comparator.reversed();
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void removeEmptyDirectories() {
        if (!Files.isDirectory(archiveRoot)) {
            return;
        }
        try (var paths = Files.walk(archiveRoot)) {
            paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).forEach(path -> {
                if (!path.equals(archiveRoot)) {
                    deleteEmptyDirectory(path);
                }
            });
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger(LibraryService.class).warn("Unable to scan empty archive directories", error);
        }
    }

    private void deleteLinkAndFile(String username, String videoId) {
        String orphanPath = repository.deleteLink(username, videoId);
        if (orphanPath != null) {
            Path directory = safePath(orphanPath).getParent();
            deleteDirectory(directory);
            Path parent = directory.getParent();
            while (parent != null && parent.startsWith(archiveRoot) && !parent.equals(archiveRoot)) {
                if (!deleteEmptyDirectory(parent)) {
                    break;
                }
                parent = parent.getParent();
            }
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

    private static boolean deleteEmptyDirectory(Path directory) {
        try {
            return Files.deleteIfExists(directory);
        } catch (java.nio.file.DirectoryNotEmptyException error) {
            return false;
        } catch (java.nio.file.NoSuchFileException error) {
            return true;
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger(LibraryService.class).warn("Unable to remove empty directory {}", directory, error);
            return false;
        }
    }
}
