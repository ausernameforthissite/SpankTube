package dev.spanktube.archive;

import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ArchiveWorker {

    private final ArchiveJobService jobs;
    private final UpstreamClient upstreamClient;
    private final CatalogClient catalogClient;
    private final YtDlpDownloader downloader;
    private final EventPublisher publisher;
    private final ArchiveProperties properties;

    ArchiveWorker(ArchiveJobService jobs, UpstreamClient upstreamClient, CatalogClient catalogClient,
            YtDlpDownloader downloader, EventPublisher publisher, ArchiveProperties properties) {
        this.jobs = jobs;
        this.upstreamClient = upstreamClient;
        this.catalogClient = catalogClient;
        this.downloader = downloader;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${spanktube.archive.worker-delay-ms:2000}")
    void work() {
        ArchiveModels.Job job = jobs.claimNextJob();
        if (job == null) {
            return;
        }
        ArchiveModels.JobItem item = jobs.claimNextItem(job.id());
        if (item == null) {
            return;
        }
        ArchiveModels.ExistingVideo metadata = null;
        Path temporaryDirectory = null;
        boolean publishAttempted = false;
        try {
            ArchiveModels.DownloadOptions options = job.options() == null
                    ? ArchiveModels.DownloadOptions.archiveDefaults() : job.options();
            if (job.kind() == ArchiveModels.Kind.DOWNLOAD) {
                ArchiveModels.ExistingVideo matching = catalogClient.matchingVariant(
                        job.ownerUsername(), item.videoId(), options).orElse(null);
                if (matching != null) {
                    jobs.completeDownload(job, matching, downloaded(matching), false);
                    return;
                }
            } else {
                ArchiveModels.ExistingVideo existing = catalogClient.existing(job.ownerUsername(), item.videoId())
                        .orElse(null);
                if (existing != null) {
                    catalogClient.prepare(job.ownerUsername(), existing.sizeBytes(), item.videoId());
                    publisher.publish(DomainEvent.of("video.archived", existing)).block();
                    jobs.completeItem(item, true, null, nextDelay());
                    return;
                }
            }

            jobs.progress(job.id(), new DownloadProgress.Snapshot(ArchiveModels.Stage.METADATA, 0, 0, 0));
            UpstreamClient.VideoDownload download = upstreamClient.video(item.videoId());
            metadata = download.metadata();
            UpstreamClient.Selection selection = download.select(options);
            jobs.metadata(job.id(), metadata.title(), selection.estimatedBytes(),
                    selection.video() == null ? null : selection.video().width(),
                    selection.video() == null ? null : selection.video().height(),
                    selection.video() == null ? null : selection.video().codecs(),
                    selection.audio() == null ? null : selection.audio().codecs());
            if (job.kind() != ArchiveModels.Kind.DOWNLOAD && selection.estimatedBytes() > 0) {
                catalogClient.prepare(job.ownerUsername(), selection.estimatedBytes(), item.videoId());
            }
            Path directory = job.kind() == ArchiveModels.Kind.DOWNLOAD
                    ? Path.of(properties.root(), ".downloads", job.id())
                    : Path.of(properties.root(), metadata.channelId(), metadata.videoId());
            if (job.kind() == ArchiveModels.Kind.DOWNLOAD) {
                temporaryDirectory = directory;
            }
            jobs.progress(job.id(), new DownloadProgress.Snapshot(ArchiveModels.Stage.DOWNLOADING,
                    0, selection.estimatedBytes(), 0));
            ArchiveModels.DownloadedFile file = downloader.download(metadata, options, directory,
                    selection.estimatedBytes(), progress -> jobs.progress(job.id(), progress));
            jobs.progress(job.id(), new DownloadProgress.Snapshot(ArchiveModels.Stage.FINISHING,
                    file.sizeBytes(), file.sizeBytes(), 0));

            if (job.kind() == ArchiveModels.Kind.DOWNLOAD) {
                jobs.completeDownload(job, metadata, file, true);
                return;
            }
            catalogClient.prepare(job.ownerUsername(), file.sizeBytes(), item.videoId());
            String quality = file.videoFormatId() == null ? metadata.quality()
                    : file.videoFormatId() + "+" + file.audioFormatId();
            ArchiveModels.ExistingVideo completed = new ArchiveModels.ExistingVideo(
                    job.ownerUsername(), metadata.channelId(), metadata.channelName(), metadata.channelHandle(),
                    metadata.channelCustomPath(), metadata.channelAvatarUrl(), metadata.channelSubscriberCount(),
                    metadata.videoId(), metadata.title(), metadata.publishedAt(), metadata.durationSeconds(),
                    metadata.archivedPath(), quality, file.sizeBytes(), file.width(), file.height(),
                    file.videoCodec(), file.audioCodec());
            publishAttempted = true;
            publisher.publish(DomainEvent.of("video.archived", completed)).block();
            jobs.completeItem(item, true, null, nextDelay());
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            org.slf4j.LoggerFactory.getLogger(ArchiveWorker.class)
                    .warn("Archive item {} failed: {}", item.videoId(), message);
            int attempt = item.attempts() + 1;
            boolean rateLimited = message.toLowerCase(java.util.Locale.ROOT).contains("429")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("too many requests");
            if (!publishAttempted && (!rateLimited || attempt >= 3)) {
                if (temporaryDirectory != null) {
                    downloader.discard(temporaryDirectory);
                } else if (metadata != null) {
                    downloader.discard(metadata);
                }
            }
            if (attempt < 3) {
                jobs.deferItem(item, rateLimited ? 60L * attempt + nextDelay() : nextDelay() * attempt, message);
            } else {
                publisher.publish(DomainEvent.of("video.archive.failed",
                        new ArchiveFailure(job.ownerUsername(), item.videoId(), message))).subscribe();
                jobs.completeItem(item, false, message, nextDelay());
            }
        }
    }

    private ArchiveModels.DownloadedFile downloaded(ArchiveModels.ExistingVideo video) {
        return downloader.inspect(Path.of(video.archivedPath()), ArchiveModels.DownloadOptions.archiveDefaults());
    }

    private long nextDelay() {
        return ThreadLocalRandom.current().nextLong(properties.minDelaySeconds(), properties.maxDelaySeconds() + 1);
    }
}
