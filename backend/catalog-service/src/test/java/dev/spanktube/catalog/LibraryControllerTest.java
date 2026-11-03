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
}
