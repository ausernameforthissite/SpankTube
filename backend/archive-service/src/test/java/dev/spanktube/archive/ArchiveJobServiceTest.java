package dev.spanktube.archive;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.spanktube.events.EventPublisher;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchiveJobServiceTest {

    private final ArchiveRepository repository = mock(ArchiveRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final ArchiveJobService service = new ArchiveJobService(repository, publisher, catalog,
            mock(YtDlpDownloader.class), new ArchiveProperties("/archive", 2, 5, Duration.ofHours(1), 2000),
            mock(ArchiveEvents.class));

    @Test
    void existingArchiveReturnsCompletedJobWithoutEnqueuing() {
        ArchiveModels.EnqueueRequest request = request();
        ArchiveModels.Job completed = mock(ArchiveModels.Job.class);
        when(catalog.existing("viewer", "3TYZjVzWazE")).thenReturn(Optional.of(mock(ArchiveModels.ExistingVideo.class)));
        when(repository.completedVideoJob("3TYZjVzWazE", "viewer")).thenReturn(completed);

        ArchiveModels.EnqueueResult result = service.enqueue(request);

        assertThat(result.created()).isFalse();
        assertThat(result.job()).isSameAs(completed);
        verify(repository, never()).createJob(any());
        verify(repository, never()).createCompletedArchive(any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void existingArchiveWithoutSingleVideoJobGetsCompletedHistoryRecord() {
        ArchiveModels.EnqueueRequest request = request();
        ArchiveModels.ExistingVideo archived = mock(ArchiveModels.ExistingVideo.class);
        ArchiveModels.Job completed = mock(ArchiveModels.Job.class);
        when(catalog.existing("viewer", "3TYZjVzWazE")).thenReturn(Optional.of(archived));
        when(repository.createCompletedArchive(request, archived)).thenReturn("completed-job");
        when(repository.job("completed-job")).thenReturn(completed);

        ArchiveModels.EnqueueResult result = service.enqueue(request);

        assertThat(result.created()).isFalse();
        assertThat(result.job()).isSameAs(completed);
        verify(repository).createCompletedArchive(request, archived);
        verifyNoInteractions(publisher);
    }

    @Test
    void removedLibraryEntryDoesNotReuseCompletedJob() {
        ArchiveModels.EnqueueRequest request = request();
        ArchiveModels.Job queued = mock(ArchiveModels.Job.class);
        when(catalog.existing("viewer", "3TYZjVzWazE")).thenReturn(Optional.empty());
        when(repository.completedVideoJob("3TYZjVzWazE", "viewer")).thenReturn(mock(ArchiveModels.Job.class));
        when(repository.createJob(request)).thenReturn("new-job");
        when(repository.job("new-job")).thenReturn(queued);
        when(publisher.publish(any())).thenReturn(Mono.empty());

        ArchiveModels.EnqueueResult result = service.enqueue(request);

        assertThat(result.created()).isTrue();
        assertThat(result.job()).isSameAs(queued);
        verify(repository).createJob(request);
        verify(repository, never()).createCompletedArchive(any(), any());
    }

    @Test
    void channelBecomesIndependentVideoJobs() {
        ArchiveModels.Job queued = mock(ArchiveModels.Job.class);
        when(catalog.existing(any(), any())).thenReturn(Optional.empty());
        when(repository.createJob(any())).thenReturn("first-job", "second-job");
        when(repository.job(any())).thenReturn(queued);
        when(publisher.publish(any())).thenReturn(Mono.empty());

        ArchiveModels.EnqueueResult result = service.enqueue(new ArchiveModels.EnqueueRequest(
                "viewer", "VIEWER", ArchiveModels.Kind.CHANNEL, "channel", List.of("one", "two", "one")));

        org.mockito.ArgumentCaptor<ArchiveModels.EnqueueRequest> requests =
                org.mockito.ArgumentCaptor.forClass(ArchiveModels.EnqueueRequest.class);
        verify(repository, org.mockito.Mockito.times(2)).createJob(requests.capture());
        assertThat(result.created()).isTrue();
        assertThat(requests.getValue().kind()).isEqualTo(ArchiveModels.Kind.VIDEO);
        assertThat(requests.getAllValues()).extracting(ArchiveModels.EnqueueRequest::targetId)
                .containsExactly("one", "two");
    }

    private static ArchiveModels.EnqueueRequest request() {
        return new ArchiveModels.EnqueueRequest("viewer", "VIEWER", ArchiveModels.Kind.VIDEO,
                "3TYZjVzWazE", List.of("3TYZjVzWazE"));
    }
}
