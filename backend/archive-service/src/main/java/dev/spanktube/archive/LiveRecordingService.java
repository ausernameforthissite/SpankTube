package dev.spanktube.archive;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class LiveRecordingService {

    private final UpstreamClient upstreamClient;
    private final LiveRecordingRepository repository;
    private final CatalogClient catalogClient;
    private final EventPublisher publisher;
    private final ArchiveProperties properties;
    private final ArchiveDownloader downloader;
    private final MediaClient mediaClient;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final String mediaServiceUrl;
    private final String ffmpegExecutable;
    private final Path root;
    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> stopRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> stoppingReasons = new ConcurrentHashMap<>();
    private final Map<UUID, LiveRecordingRequest> requestedQualities = new ConcurrentHashMap<>();
    private final AtomicLong lastQuotaCheck = new AtomicLong();

    LiveRecordingService(UpstreamClient upstreamClient, LiveRecordingRepository repository,
            CatalogClient catalogClient, EventPublisher publisher, ArchiveProperties properties,
            ArchiveDownloader downloader, MediaClient mediaClient, ObjectMapper objectMapper,
            @Qualifier("liveRecordingExecutor") Executor executor,
            @Value("${spanktube.media.url:http://localhost:8082}") String mediaServiceUrl,
            @Value("${spanktube.archive.ffmpeg-executable:ffmpeg}") String ffmpegExecutable) {
        this.upstreamClient = upstreamClient;
        this.repository = repository;
        this.catalogClient = catalogClient;
        this.publisher = publisher;
        this.properties = properties;
        this.downloader = downloader;
        this.mediaClient = mediaClient;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.mediaServiceUrl = mediaServiceUrl.endsWith("/") ? mediaServiceUrl.substring(0, mediaServiceUrl.length() - 1)
                : mediaServiceUrl;
        this.ffmpegExecutable = ffmpegExecutable;
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    LiveRecordingRepository.StartResult start(String owner, String videoId) {
        return start(owner, videoId, LiveRecordingRequest.bestAvailable());
    }

    LiveRecordingRepository.StartResult start(String owner, String videoId, LiveRecordingRequest request) {
        if (request == null) {
            request = LiveRecordingRequest.bestAvailable();
        }
        if (request.height() != null && request.height() <= 0
                || request.bandwidth() != null && request.bandwidth() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recording quality");
        }
        UpstreamClient.LiveSource source = upstreamClient.liveVideo(videoId);
        UUID id = UUID.randomUUID();
        Path output = output(id, source.metadata());
        LiveRecordingRepository.StartResult result = repository.start(id, owner, videoId,
                source.metadata().withOwnerUsername(owner).withArchivedPath(output.toString()), source.manifestUrl(), output);
        if (result.created()) {
            requestedQualities.put(id, request);
            executor.execute(() -> record(id));
        }
        return result;
    }

    LiveRecording stop(String owner, String videoId) {
        LiveRecording recording = repository.stop(owner, videoId);
        if (recording == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No active recording for this live stream");
        }
        stopProcess(UUID.fromString(recording.id()));
        return repository.get(UUID.fromString(recording.id()));
    }

    LiveRecording status(String owner, String videoId) {
        repository.touchActive(owner, videoId);
        return repository.latest(owner, videoId);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterrupted() {
        repository.recoverInterrupted();
    }

    private void record(UUID id) {
        LiveRecording recording = repository.get(id);
        LiveRecordingRepository.Source source = repository.source(id);
        if (recording == null || source == null) {
            return;
        }
        try {
            ArchiveModels.ExistingVideo metadata = source.metadata();
            Path output = Path.of(metadata.archivedPath()).toAbsolutePath().normalize();
            if (!output.startsWith(root)) {
                throw new ArchiveDownloadException("Recording path escapes archive root");
            }
            Path directory = Files.createDirectories(output.getParent());
            Files.deleteIfExists(output);
            List<String> command = new ArrayList<>(List.of(ffmpegExecutable, "-y", "-hide_banner", "-loglevel", "warning",
                    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "15"));
            if (source.manifestUrl().endsWith(".m3u8")) {
                command.addAll(List.of("-live_start_index", "-1"));
            }
            Path manifest = directory.resolve(source.manifestUrl().endsWith(".m3u8") ? "master.m3u8" : "source.mpd");
            mediaClient.downloadResource(source.manifestUrl(), manifest);
            LiveRecordingRequest quality = requestedQualities.getOrDefault(id, LiveRecordingRequest.bestAvailable());
            LiveRecordingManifest.Selection selection = LiveRecordingManifest.select(manifest, mediaServiceUrl,
                    quality.height(), quality.bandwidth());
            for (String input : selection.inputs()) {
                command.addAll(List.of("-i", input));
            }
            for (String map : selection.maps()) {
                command.addAll(List.of("-map", map));
            }
            command.addAll(List.of("-c", "copy", "-c:s", "srt", "-metadata", "creation_time=" + recording.startedAt(),
                    "-metadata", "title=" + metadata.title(), output.toString()));
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("ffmpeg.log").toFile()).start();
            processes.put(id, process);
            if (stopRequested.contains(id)) {
                stopProcess(id);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 && !stopRequested.contains(id)) {
                throw new ArchiveDownloadException("ffmpeg exited with code " + exitCode);
            }
            ArchiveModels.DownloadedFile file = downloader.inspect(output,
                    ArchiveModels.DownloadOptions.archiveDefaults());
            String fileName = LiveRecordingFilenames.of(metadata.title(), metadata.videoId(),
                    Instant.parse(recording.startedAt()), file);
            writeInfo(recording, metadata, file, fileName);
            try {
                mediaClient.downloadResource("/media/v/" + metadata.videoId() + "/thumbnail",
                        directory.resolve("video.jpg"));
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(LiveRecordingService.class)
                        .warn("Unable to save thumbnail for live recording {}: {}", id, error.getMessage());
            }
            catalogClient.prepare(recording.ownerUsername(), file.sizeBytes(), recording.videoId(), true);
            ArchiveModels.ExistingVideo completed = new ArchiveModels.ExistingVideo(
                    recording.ownerUsername(), metadata.channelId(), metadata.channelName(), metadata.channelHandle(),
                    metadata.channelCustomPath(), metadata.channelAvatarUrl(), metadata.channelSubscriberCount(),
                    metadata.videoId(), metadata.title(), recording.startedAt(), file.durationSeconds(), output.toString(),
                    "LIVE_RECORDING", file.sizeBytes(), file.width(), file.height(), file.videoCodec(),
                    file.audioCodec(), metadata.description(), file.audioBitrate());
            Files.setLastModifiedTime(output, java.nio.file.attribute.FileTime.from(Instant.parse(recording.startedAt())));
            publisher.publish(DomainEvent.of("video.archived", completed)).block();
            repository.complete(id, file, fileName, stoppingReasons.get(id));
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            org.slf4j.LoggerFactory.getLogger(LiveRecordingService.class)
                    .warn("Live recording {} failed: {}", id, message);
            repository.fail(id, stoppingReasons.getOrDefault(id, message));
        } finally {
            processes.remove(id);
            stopRequested.remove(id);
            stoppingReasons.remove(id);
            requestedQualities.remove(id);
        }
    }

    @Scheduled(fixedDelay = 5000)
    void progressAndQuota() {
        Map<String, Long> owners = new java.util.HashMap<>();
        Map<String, String> videoIds = new java.util.HashMap<>();
        for (LiveRecordingRepository.ActiveFile file : repository.activeFiles()) {
            if (file.lastSeenAt().isBefore(Instant.now().minus(java.time.Duration.ofSeconds(45)))) {
                stoppingReasons.put(file.id(), "Recording stopped because the watch page was closed");
                repository.markStopping(file.id());
                stopProcess(file.id());
                continue;
            }
            long bytes;
            try {
                bytes = Files.size(file.path());
            } catch (IOException error) {
                bytes = 0;
            }
            repository.progress(file.id(), bytes);
            owners.merge(file.owner(), bytes, Long::sum);
            videoIds.put(file.owner(), file.videoId());
        }
        long now = System.nanoTime();
        if (owners.isEmpty() || now - lastQuotaCheck.get() < TimeUnit.SECONDS.toNanos(15)) {
            return;
        }
        if (!lastQuotaCheck.compareAndSet(lastQuotaCheck.get(), now)) {
            return;
        }
        owners.forEach((owner, bytes) -> {
            try {
                catalogClient.prepare(owner, bytes, videoIds.get(owner), true);
            } catch (RuntimeException error) {
                repository.activeFiles().stream().filter(file -> file.owner().equals(owner))
                        .forEach(file -> {
                            stoppingReasons.put(file.id(), "Recording stopped by archive quota");
                            repository.markStopping(file.id());
                            stopProcess(file.id());
                        });
            }
        });
    }

    private void stopProcess(UUID id) {
        stopRequested.add(id);
        Process process = processes.get(id);
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            process.getOutputStream().write("q\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (IOException error) {
            process.destroy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }

    private Path output(UUID id, ArchiveModels.ExistingVideo metadata) {
        Path output = root.resolve(metadata.channelId()).resolve(metadata.videoId())
                .resolve(id.toString()).resolve("video.mkv").normalize();
        if (!output.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recording path escapes archive root");
        }
        return output;
    }

    private void writeInfo(LiveRecording recording, ArchiveModels.ExistingVideo metadata,
            ArchiveModels.DownloadedFile file, String fileName) throws IOException {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("id", metadata.videoId());
        info.put("title", metadata.title());
        info.put("description", metadata.description());
        info.put("channel_id", metadata.channelId());
        info.put("channel", metadata.channelName());
        info.put("extractor", "live-recording");
        info.put("webpage_url", "https://www.youtube.com/watch?v=" + metadata.videoId());
        info.put("format_id", "LIVE_RECORDING");
        info.put("recording_started_at", recording.startedAt());
        info.put("recording_finished_at", Instant.now().toString());
        info.put("filename", fileName);
        if (file.durationSeconds() != null) info.put("duration", file.durationSeconds());
        if (file.audioBitrate() != null) info.put("abr", file.audioBitrate() / 1000.0);
        Path output = Path.of(metadata.archivedPath());
        objectMapper.writeValue(output.getParent().resolve("info.json").toFile(), info);
    }

    @PreDestroy
    void stopAll() {
        processes.values().forEach(Process::destroy);
    }
}
