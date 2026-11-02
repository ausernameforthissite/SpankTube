package dev.spanktube.upstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientUpstreamProviderTest {

    private final NewPipeUpstreamProvider primary = mock(NewPipeUpstreamProvider.class);
    private final YtDlpUpstreamProvider fallback = mock(YtDlpUpstreamProvider.class);
    private final ResilientUpstreamProvider provider = new ResilientUpstreamProvider(primary, fallback);

    @Test
    void fallsBackWhenNewPipeFails() {
        VideoDetails expected = mock(VideoDetails.class);
        when(primary.video("3TYZjVzWazE")).thenThrow(UpstreamException.failed("changed response"));
        when(fallback.video("3TYZjVzWazE")).thenReturn(expected);

        assertThat(provider.video("3TYZjVzWazE")).isSameAs(expected);
    }

    @Test
    void doesNotHideInvalidInputWithFallback() {
        when(primary.video("bad")).thenThrow(new IllegalArgumentException("Invalid videoId"));

        assertThatIllegalArgumentException().isThrownBy(() -> provider.video("bad"));
        verify(fallback, never()).video("bad");
    }
}
