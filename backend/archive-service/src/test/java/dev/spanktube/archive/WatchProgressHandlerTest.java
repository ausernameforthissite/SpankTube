package dev.spanktube.archive;

import java.util.Optional;

import dev.spanktube.events.EventSubscriber;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchProgressHandlerTest {

    @Test
    void globallyArchivedVideoDoesNotCreateAutomaticJob() {
        ArchiveJobService jobs = mock(ArchiveJobService.class);
        CatalogClient catalog = mock(CatalogClient.class);
        when(catalog.existing("viewer", "3TYZjVzWazE"))
                .thenReturn(Optional.of(mock(ArchiveModels.ExistingVideo.class)));
        UserClient users = mock(UserClient.class);
        when(users.autoArchive("viewer")).thenReturn(true);
        WatchProgressHandler handler = new WatchProgressHandler(mock(EventSubscriber.class), jobs, catalog, users);

        handler.archiveIfNeeded(new WatchProgressHandler.WatchProgressEvent(
                "viewer", "3TYZjVzWazE", 30, 600, 5)).block();

        verify(jobs, never()).enqueue(any());
    }

    @Test
    void disabledAutomaticArchivingDoesNotCreateJob() {
        ArchiveJobService jobs = mock(ArchiveJobService.class);
        UserClient users = mock(UserClient.class);
        when(users.autoArchive("viewer")).thenReturn(false);
        WatchProgressHandler handler = new WatchProgressHandler(mock(EventSubscriber.class), jobs,
                mock(CatalogClient.class), users);

        handler.archiveIfNeeded(new WatchProgressHandler.WatchProgressEvent(
                "viewer", "3TYZjVzWazE", 30, 600, 5)).block();

        verify(jobs, never()).enqueue(any());
    }
}
