package dev.spanktube.upstream;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataControllerTest {

    private final UpstreamProvider provider = mock(UpstreamProvider.class);
    private final MetadataCacheService cache = mock(MetadataCacheService.class);
    private final MetadataController controller = new MetadataController(
            provider, mock(MetadataLogService.class), cache);

    @BeforeEach
    void answerCache() {
        when(cache.get(anyString(), anyString(), any(), any(), any(Duration.class)))
                .thenAnswer(invocation -> Mono.fromCallable(((Supplier<?>) invocation.getArgument(3))::get));
    }

    @Test
    void doesNotLoadPlaylistsForVideosTab() {
        when(provider.channel("channel", null)).thenReturn(channel());

        PublicChannel result = controller.channel("channel", null, null, false).block();

        assertThat(result).isNotNull();
        assertThat(result.playlists()).isEmpty();
        verify(provider, never()).channelPlaylists("channel", null);
    }

    @Test
    void loadsPlaylistsOnlyWhenRequested() {
        SearchResults playlists = new SearchResults(List.of(mock(SearchItem.class)), null);
        when(provider.channel("channel", null)).thenReturn(channel());
        when(provider.channelPlaylists("channel", null)).thenReturn(playlists);

        PublicChannel result = controller.channel("channel", null, null, true).block();

        assertThat(result).isNotNull();
        assertThat(result.playlists()).isEqualTo(playlists.items());
    }

    private static ChannelDetails channel() {
        return new ChannelDetails("channel", "Channel", "channel", null, null, 100L,
                List.of(), List.of(), null);
    }
}
