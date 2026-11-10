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
    private final LiveDashClient liveClient = mock(LiveDashClient.class);
    private final ResilientUpstreamProvider provider = new ResilientUpstreamProvider(primary, kids, fallback, liveClient);

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
    void usesVisionOsWhenNewPipeIsBlocked() {
        VideoDetails expected = mock(VideoDetails.class);
        when(primary.video("rFZHOHl-L8A")).thenThrow(UpstreamException.antiBot("LOGIN_REQUIRED"));
        when(liveClient.liveVideo("rFZHOHl-L8A")).thenReturn(Optional.of(expected));

        assertThat(provider.video("rFZHOHl-L8A")).isSameAs(expected);
        verify(kids, never()).video("rFZHOHl-L8A");
        verify(fallback, never()).video("rFZHOHl-L8A");
    }

    @Test
    void doesNotHideMissingLiveDashWithAnotherAdapter() {
        when(primary.video("rFZHOHl-L8A")).thenThrow(UpstreamException.dashUnavailable("No live DASH"));
        when(fallback.video("rFZHOHl-L8A")).thenThrow(UpstreamException.dashUnavailable("No live DASH"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.video("rFZHOHl-L8A"))
                .isInstanceOfSatisfying(UpstreamException.class,
                        error -> assertThat(error.reason()).isEqualTo(UpstreamException.Reason.DASH_UNAVAILABLE));
        verify(liveClient).liveVideo("rFZHOHl-L8A");
    }

    @Test
    void doesNotHideInvalidInputWithFallback() {
        when(primary.video("bad")).thenThrow(new IllegalArgumentException("Invalid videoId"));

        assertThatIllegalArgumentException().isThrownBy(() -> provider.video("bad"));
        verify(kids, never()).video("bad");
        verify(fallback, never()).video("bad");
    }
}
