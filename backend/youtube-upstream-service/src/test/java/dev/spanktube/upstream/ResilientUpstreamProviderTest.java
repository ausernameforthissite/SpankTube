package dev.spanktube.upstream;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void retriesTransientAntibotOnceBeforeFallback() {
        VideoDetails expected = mock(VideoDetails.class);
        when(primary.video("gTGdQ9mfJL4"))
                .thenThrow(UpstreamException.antiBot("LOGIN_REQUIRED"))
                .thenReturn(expected);

        assertThat(provider.video("gTGdQ9mfJL4")).isSameAs(expected);
        verify(primary, org.mockito.Mockito.times(2)).video("gTGdQ9mfJL4");
        verify(liveClient, never()).liveVideo("gTGdQ9mfJL4");
        verify(fallback, never()).video("gTGdQ9mfJL4");
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

        assertThatThrownBy(() -> provider.video("rFZHOHl-L8A"))
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

    @Test
    void retriesTransientPageFailureBeforeYtDlp() {
        SearchResults expected = results(30, "np:next");
        when(primary.channelVideos("channel", null))
                .thenThrow(UpstreamException.failed("HTML instead of JSON"))
                .thenReturn(expected);

        assertThat(provider.channelVideos("channel", null)).isSameAs(expected);
        verify(primary, org.mockito.Mockito.times(2)).channelVideos("channel", null);
        verify(fallback, never()).channelVideos("channel", null);
    }

    @Test
    void translatesNewPipeContinuationForYtDlp() {
        when(primary.channelVideos("channel", null)).thenReturn(results(30, "np:next"));
        when(primary.channelVideos("channel", "np:next")).thenThrow(UpstreamException.failed("HTML instead of JSON"));
        SearchResults expected = results(20, "50");
        when(fallback.channelVideos("channel", "30")).thenReturn(expected);

        assertThat(provider.channelVideos("channel", null).nextCursor()).isEqualTo("np:next");
        assertThat(provider.channelVideos("channel", "np:next")).isSameAs(expected);
    }

    @Test
    void preservesPrimaryErrorWhenContinuationCannotBeTranslated() {
        when(primary.channelVideos("channel", "np:unknown"))
                .thenThrow(UpstreamException.failed("HTML instead of JSON"));
        when(fallback.channelVideos("channel", "np:unknown"))
                .thenThrow(new IllegalArgumentException("Invalid cursor"));

        assertThatThrownBy(() -> provider.channelVideos("channel", "np:unknown"))
                .isInstanceOfSatisfying(UpstreamException.class,
                        error -> assertThat(error.getMessage()).contains("HTML instead of JSON"));
    }

    private static SearchResults results(int count, String nextCursor) {
        return new SearchResults(Collections.nCopies(count, mock(SearchItem.class)), nextCursor);
    }
}
