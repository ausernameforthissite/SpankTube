package dev.spanktube.archive;

import java.util.Optional;
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
        boolean publishAttempted = false;
        try {
            ArchiveModels.ExistingVideo existing = catalogClient.existing(job.ownerUsername(), item.videoId())
                    .orElse(null);
            if (existing != null) {
                catalogClient.prepare(job.ownerUsername(), existing.sizeBytes(), item.videoId());
                publisher.publish(DomainEvent.of("video.archived", existing)).block();
                jobs.completeItem(item, true, null, nextDelay());
                return;
            }

            UpstreamClient.VideoDownload download = upstreamClient.video(item.videoId());
            metadata = download.metadata();
            if (download.estimatedBytes() > 0) {
                catalogClient.prepare(job.ownerUsername(), download.estimatedBytes(), item.videoId());
            }
            long size = downloader.download(metadata);
            catalogClient.prepare(job.ownerUsername(), size, item.videoId());
            ArchiveModels.ExistingVideo completed = new ArchiveModels.ExistingVideo(
                    job.ownerUsername(), metadata.channelId(), metadata.channelName(), metadata.channelHandle(),
                    metadata.channelCustomPath(), metadata.channelAvatarUrl(), metadata.channelSubscriberCount(),
                    metadata.videoId(), metadata.title(), metadata.publishedAt(), metadata.durationSeconds(),
                    metadata.archivedPath(), metadata.quality(), size);
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
            if (metadata != null && !publishAttempted && (!rateLimited || attempt >= 3)) {
                downloader.discard(metadata);
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

    private long nextDelay() {
        return ThreadLocalRandom.current().nextLong(properties.minDelaySeconds(), properties.maxDelaySeconds() + 1);
    }
}
