package dev.spanktube.archive;

import java.util.List;

import dev.spanktube.events.DomainEvent;
import dev.spanktube.events.EventPublisher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ArchiveJobService {

    private final ArchiveRepository repository;
    private final EventPublisher publisher;

    ArchiveJobService(ArchiveRepository repository, EventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Transactional
    public String enqueue(ArchiveModels.EnqueueRequest request) {
        if (request.videoIds().isEmpty()) {
            throw new IllegalArgumentException("No videos to archive");
        }
        if (request.kind() == ArchiveModels.Kind.VIDEO) {
            String existing = repository.activeVideoJob(request.videoIds().getFirst(), request.owner());
            if (existing != null) {
                return existing;
            }
        }
        String jobId = repository.createJob(request);
        publisher.publish(DomainEvent.of("video.archive.requested", new ArchiveRequested(
                jobId, request.owner(), request.kind().name(), request.targetId(), request.videoIds()))).subscribe();
        return jobId;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedJobs() {
        repository.recoverInterruptedJobs();
    }

    @Transactional
    public ArchiveModels.Job claimNextJob() {
        ArchiveModels.Job job = repository.nextQueuedJob();
        if (job != null) {
            repository.markJobRunning(job.id());
        }
        return job;
    }

    @Transactional
    public ArchiveModels.JobItem claimNextItem(String jobId) {
        ArchiveModels.JobItem item = repository.nextQueuedItem(jobId);
        if (item != null) {
            repository.markItemRunning(item.id());
        }
        return item;
    }

    @Transactional
    public void completeItem(ArchiveModels.JobItem item, boolean success, String error, long nextDelaySeconds) {
        repository.completeItem(item.id(), success, error, nextDelaySeconds);
        repository.finishJob(item.jobId());
    }

    void deferItem(ArchiveModels.JobItem item, long delaySeconds, String error) {
        repository.deferItem(item.id(), delaySeconds, error);
    }

    List<ArchiveModels.Job> jobs(String owner, boolean admin, String status) {
        return repository.jobs(owner, admin, status);
    }

    ArchiveModels.Job job(String jobId) {
        return repository.job(jobId);
    }
}
