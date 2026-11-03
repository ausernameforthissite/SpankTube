package dev.spanktube.catalog;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPlaylistServiceTest {

    private final CatalogRepository repository = mock(CatalogRepository.class);
    private final UserPlaylistService service = new UserPlaylistService(repository);

    @Test
    void createsPlaylistAndAddsInitialVideo() {
        LibraryModels.UserPlaylist created = new LibraryModels.UserPlaylist("id", "My list", "viewer", 1,
                List.of("dQw4w9WgXcQ"));
        when(repository.userPlaylist(anyString())).thenReturn(Optional.of(created));

        LibraryModels.UserPlaylist result = service.create("viewer", "  My list  ", "dQw4w9WgXcQ");

        assertThat(result).isSameAs(created);
        verify(repository).createUserPlaylist(anyString(), org.mockito.ArgumentMatchers.eq("viewer"),
                org.mockito.ArgumentMatchers.eq("My list"), org.mockito.ArgumentMatchers.eq("dQw4w9WgXcQ"));
    }

    @Test
    void onlyOwnerCanAddVideo() {
        when(repository.userPlaylist("id")).thenReturn(Optional.of(
                new LibraryModels.UserPlaylist("id", "List", "owner", 1, List.of("dQw4w9WgXcQ"))));

        assertThatThrownBy(() -> service.addVideo("id", "other", "dQw4w9WgXcQ"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).addUserVideo("id", "dQw4w9WgXcQ");
    }

    @Test
    void rejectsEmptyTitle() {
        assertThatThrownBy(() -> service.create("viewer", "  ", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
