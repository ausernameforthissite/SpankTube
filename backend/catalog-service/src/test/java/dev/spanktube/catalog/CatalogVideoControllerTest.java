package dev.spanktube.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogVideoControllerTest {

    @Test
    void loadsVideoSponsorBlockAndDislikesInParallel() throws Exception {
        UpstreamClient upstream = mock(UpstreamClient.class);
        SponsorBlockClient sponsorBlock = mock(SponsorBlockClient.class);
        RydClient ryd = mock(RydClient.class);
        CountDownLatch sponsorBlockStarted = new CountDownLatch(1);
        CountDownLatch rydStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, Object> video = new HashMap<>();
        video.put("videoId", "video");
        when(upstream.video("video", null)).thenReturn(video);
        when(sponsorBlock.segments("video")).thenAnswer(invocation -> {
            sponsorBlockStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return List.of();
        });
        when(ryd.dislikes("video")).thenAnswer(invocation -> {
            rydStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return 42L;
        });
        CatalogVideoController controller = new CatalogVideoController(upstream, sponsorBlock, ryd);

        java.util.concurrent.CompletableFuture<Map<String, Object>> result = new java.util.concurrent.CompletableFuture<>();
        controller.video("video", null).subscribe(result::complete, result::completeExceptionally);
        assertThat(sponsorBlockStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(rydStarted.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        Map<String, Object> response = result.get(2, TimeUnit.SECONDS);

        assertThat(response).isNotNull();
        assertThat(response.get("videoId")).isEqualTo("video");
        assertThat(response.get("sponsorBlock")).isEqualTo(List.of());
        assertThat(response.get("dislikeCountEstimate")).isEqualTo(42L);
    }
}
