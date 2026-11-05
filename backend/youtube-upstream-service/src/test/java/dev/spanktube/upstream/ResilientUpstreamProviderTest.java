package dev.spanktube.upstream;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientUpstreamProviderTest {

    private final NewPipeUpstreamProvider primary = mock(NewPipeUpstreamProvider.class);
    private final KidsVideoClient kids = mock(KidsVideoClient.class);
    private final YtDlpUpstreamProvider fallback = mock(YtDlpUpstreamProvider.class);
    private final ResilientUpstreamProvider provider = new ResilientUpstreamProvider(primary, kids, fallback);

    @Test
    void fallsBackWhenVideoIsNotMadeForKids() {
        VideoDetails expected = mock(VideoDetails.class);
        when(primary.video("3TYZjVzWazE")).thenThrow(UpstreamException.failed("changed response"));
        when(kids.video("3TYZjVzWazE")).thenReturn(Optional.empty());
        when(fallback.video("3TYZjVzWazE")).thenReturn(expected);

        assertThat(provider.video("3TYZjVzWazE")).isSameAs(expected);
    }

    @Test
    void usesKidsClientInsteadOfYtDlpWhenMarkerIsPresent() {
        VideoDetails expected = mock(VideoDetails.class);
        when(primary.video("38a0f-fXZCc")).thenThrow(UpstreamException.failed("VISIONOS is unsupported"));
        when(kids.video("38a0f-fXZCc")).thenReturn(Optional.of(expected));

        assertThat(provider.video("38a0f-fXZCc")).isSameAs(expected);
        verify(fallback, never()).video("38a0f-fXZCc");
    }

    @Test
    void doesNotHideInvalidInputWithFallback() {
        when(primary.video("bad")).thenThrow(new IllegalArgumentException("Invalid videoId"));

        assertThatIllegalArgumentException().isThrownBy(() -> provider.video("bad"));
        verify(kids, never()).video("bad");
        verify(fallback, never()).video("bad");
    }
}
