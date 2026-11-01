package dev.spanktube.catalog;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryServiceTest {

    private final CatalogRepository repository = mock(CatalogRepository.class);
    private final UserQuotaClient quotaClient = mock(UserQuotaClient.class);

    @TempDir
    Path archiveRoot;

    @Test
    void existingLinkDoesNotReserveItsQuotaAgain() {
        when(repository.hasLink("viewer", "video")).thenReturn(true);
        LibraryService service = service();

        service.prepare("viewer", 100, "video");

        verify(quotaClient, never()).resolve("viewer");
        verify(repository, never()).oldestLink("viewer");
    }

    @Test
    void registrationUsesTheExistingQuotaPreparation() {
        ArchiveCompletedEvent event = event("viewer", "video");
        LibraryService service = service();

        service.register(event);

        verify(repository).upsert(event);
        verify(quotaClient, never()).resolve("viewer");
    }

    @Test
    void lruEvictionKeepsSharedFileAndRemovesOrphan() throws Exception {
        when(quotaClient.resolve("viewer")).thenReturn(new LibraryModels.QuotaResponse(
                "viewer", "VIEWER", null, 100, 90));
        when(repository.hasLink("viewer", "new")).thenReturn(false);
        when(repository.usage("viewer")).thenReturn(85L, 0L, 85L, 0L);
        when(repository.oldestLink("viewer")).thenReturn("old");
        when(repository.deleteLink("viewer", "old")).thenReturn(null, "channel/old/video.mkv");
        Path shared = Files.createDirectories(archiveRoot.resolve("channel/shared")).resolve("video.mkv");
        Files.writeString(shared, "shared");
        Path orphan = Files.createDirectories(archiveRoot.resolve("channel/old")).resolve("video.mkv");
        Files.writeString(orphan, "orphan");
        LibraryService service = service();

        service.prepare("viewer", 10, "new");
        service.prepare("viewer", 10, "new");

        assertThat(shared).exists();
        assertThat(orphan).doesNotExist();
    }

    private LibraryService service() {
        return new LibraryService(repository, quotaClient, archiveRoot.toString());
    }

    private static ArchiveCompletedEvent event(String owner, String videoId) {
        return new ArchiveCompletedEvent(owner, "channel", "Channel", "handle", null,
                "https://yt3.googleusercontent.com/avatar", 1L, videoId, "Video", null,
                100L, "channel/" + videoId + "/video.mkv", "bv*+ba", 100);
    }
}
