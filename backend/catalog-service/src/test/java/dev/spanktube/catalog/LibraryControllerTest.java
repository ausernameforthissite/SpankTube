package dev.spanktube.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LibraryControllerTest {

    private final LibraryService libraryService = mock(LibraryService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new LibraryController(libraryService, new IdentityService())).build();

    @Test
    void servesGlobalArchiveLocations() throws Exception {
        when(libraryService.locations(null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/library/locations")
                        .param("scope", "global")
                        .header("X-SpankTube-User", "viewer")
                        .header("X-SpankTube-Roles", "ROLE_VIEWER"))
                .andExpect(status().isOk());

        verify(libraryService).locations(null);
    }

    @Test
    void servesArchivedPlaylistIds() throws Exception {
        when(libraryService.playlistIds("viewer", "channel")).thenReturn(List.of("p1", "p2"));

        mvc.perform(get("/internal/v1/library/playlist-ids")
                        .param("username", "viewer").param("channelId", "channel"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0]").value("p1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1]").value("p2"));

        verify(libraryService).playlistIds("viewer", "channel");
    }
}
