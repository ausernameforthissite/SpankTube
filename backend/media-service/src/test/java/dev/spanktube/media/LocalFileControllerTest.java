package dev.spanktube.media;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalFileControllerTest {

    @Test
    void deniedUserCannotBrowseOrResolveMedia() {
        LocalFileAccessClient access = mock(LocalFileAccessClient.class);
        LocalFileService files = mock(LocalFileService.class);
        LocalFilePlaybackService playback = mock(LocalFilePlaybackService.class);
        LocalFileController controller = new LocalFileController(access, files, playback);
        HttpHeaders headers = new HttpHeaders();
        when(access.check(headers)).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));

        assertThatThrownBy(() -> controller.browse("", headers).block())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.manifest("file", headers).block())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.segment("file", "init-v.m4s", headers).block())
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(files, playback);
    }
}
