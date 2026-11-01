package dev.spanktube.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class ArchiveJobService {

    private final ArchiveRepository repository;
    private final EventPublisher publisher;
    private final CatalogClient catalogClient;
    private final YtDlpDownloader downloader;
    private final ArchiveProperties properties;
    private final ArchiveEvents events;
    private final AtomicLong lastProgress = new AtomicLong();

    ArchiveJobService(ArchiveRepository repository, EventPublisher publisher, CatalogClient catalogClient,
            YtDlpDownloader downloader, ArchiveProperties properties, ArchiveEvents events) {
        this.repository = repository;
        this.publisher = publisher;
        this.catalogClient = catalogClient;
        this.downloader = downloader;
        this.properties = properties;
        this.events = events;
    }

    @Transactional
    public ArchiveModels.EnqueueResult enqueue(ArchiveModels.EnqueueRequest request) {
        if (request.videoIds().isEmpty()) {
            throw new IllegalArgumentException("No videos to archive");
        }
        if (request.kind() == ArchiveModels.Kind.VIDEO) {
            String existing = repository.activeVideoJob(request.videoIds().getFirst(), request.owner());
            if (existing != null) {
                return new ArchiveModels.EnqueueResult(repository.job(existing), false);
            }
        }
        if (request.kind() == ArchiveModels.Kind.DOWNLOAD) {
            ArchiveModels.Job active = repository.activeDownloadJob(request.targetId(), request.owner(),
                    request.options().signature());
            if (active != null) {
                return new ArchiveModels.EnqueueResult(active, false);
            }
            ArchiveModels.Job reusable = repository.reusableDownload(request.targetId(), request.owner(),
                    request.options().signature());
            if (reusable != null) {
                return new ArchiveModels.EnqueueResult(reusable, false);
            }
            if (request.options().archiveEquivalent()) {
                ArchiveModels.ExistingVideo existing = catalogClient.existing(request.owner(), request.targetId())
                        .orElse(null);
                if (existing != null) {
                    String jobId = repository.createJob(request);
                    ArchiveModels.DownloadedFile file = downloader.inspect(Path.of(existing.archivedPath()),
                            ArchiveModels.DownloadOptions.archiveDefaults());
                    repository.completeDownload(jobId, existing.title(), existing.archivedPath(),
                            ArchiveFilenames.of(existing.title(), existing.videoId(), file), false, file);
                    notifyRequested(jobId, request);
                    return new ArchiveModels.EnqueueResult(repository.job(jobId), true);
                }
            }
        }
        String jobId = repository.createJob(request);
        notifyRequested(jobId, request);
        return new ArchiveModels.EnqueueResult(repository.job(jobId), true);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedJobs() {
        repository.recoverInterruptedJobs();
        afterCommit(events::publishQueueUpdate);
    }

    @Transactional
    public ArchiveModels.Job claimNextJob() {
        ArchiveModels.Job job = repository.nextQueuedJob();
        if (job != null) {
            repository.markJobRunning(job.id());
            afterCommit(events::publishQueueUpdate);
        }
        return job;
    }

    @Transactional
    public ArchiveModels.JobItem claimNextItem(String jobId) {
        ArchiveModels.JobItem item = repository.nextQueuedItem(jobId);
        if (item != null) {
            repository.markItemRunning(item.id());
            afterCommit(events::publishQueueUpdate);
        }
        return item;
    }

    void metadata(String jobId, String title, long estimatedBytes, Integer width, Integer height,
            String videoCodec, String audioCodec) {
        repository.updateMetadata(jobId, title, estimatedBytes, width, height, videoCodec, audioCodec);
        events.publishQueueUpdate();
    }

    void progress(String jobId, DownloadProgress.Snapshot progress) {
        long now = System.nanoTime();
        long previous = lastProgress.get();
        if (progress.stage() == ArchiveModels.Stage.DOWNLOADING && now - previous < 250_000_000) {
            return;
        }
        if (lastProgress.compareAndSet(previous, now)) {
            repository.updateProgress(jobId, progress.stage(), progress.downloadedBytes(),
                    progress.totalBytes(), progress.speedBps());
            events.publishQueueUpdate();
        }
    }

    @Transactional
    public void completeItem(ArchiveModels.JobItem item, boolean success, String error, long nextDelaySeconds) {
        repository.completeItem(item.id(), success, error, nextDelaySeconds);
        repository.finishJob(item.jobId());
        afterCommit(events::publishQueueUpdate);
    }

    @Transactional
    public void completeDownload(ArchiveModels.Job job, ArchiveModels.ExistingVideo metadata,
            ArchiveModels.DownloadedFile file, boolean temporary) {
        String filename = ArchiveFilenames.of(metadata.title(), metadata.videoId(), file);
        String path = metadata.archivedPath();
        if (temporary) {
            Path target = Path.of(properties.root(), ".downloads", job.id(), filename);
            Path source = target.getParent().resolve("mp3".equals(file.extension()) ? "audio.mp3" : "video.mkv");
            try {
                java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException error) {
                throw new ArchiveDownloadException("Unable to name temporary download: " + error.getMessage(), error);
            }
            path = target.toString();
        }
        repository.completeDownload(job.id(), metadata.title(), path, filename, temporary, file);
        afterCommit(events::publishQueueUpdate);
    }

    void deferItem(ArchiveModels.JobItem item, long delaySeconds, String error) {
        repository.deferItem(item.id(), delaySeconds, error);
        afterCommit(events::publishQueueUpdate);
    }

    List<ArchiveModels.Job> jobs(String owner, boolean admin, String status) {
        return repository.jobs(owner, admin, status);
    }

    ArchiveModels.Job job(String jobId) {
        return repository.job(jobId);
    }

    ArchiveRepository.JobFile file(String jobId) {
        return repository.file(jobId);
    }

    long waitingAhead(String username) {
        return repository.waitingAhead(username);
    }

    @Scheduled(cron = "${spanktube.archive.cleanup-cron:0 */15 * * * *}")
    void removeExpiredDownloads() {
        for (ArchiveRepository.ExpiredFile file : repository.expiredTemporaryFiles()) {
            downloader.discard(file.path().getParent());
            if (java.nio.file.Files.notExists(file.path())) {
                repository.removeExpiredFile(file.jobId());
            } else {
                org.slf4j.LoggerFactory.getLogger(ArchiveJobService.class)
                        .warn("Unable to delete expired download {}", file.path());
            }
        }
        events.publishQueueUpdate();
    }

    private void notifyRequested(String jobId, ArchiveModels.EnqueueRequest request) {
        afterCommit(() -> {
            publisher.publish(DomainEvent.of("video.archive.requested", new ArchiveRequested(
                    jobId, request.owner(), request.kind().name(), request.targetId(), request.videoIds()))).subscribe();
            events.publishQueueUpdate();
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
