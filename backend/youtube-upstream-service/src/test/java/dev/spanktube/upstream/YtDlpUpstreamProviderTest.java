package dev.spanktube.upstream;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpUpstreamProviderTest {

    @Test
    void returnsCompletedProcessOutput() throws Exception {
        assertThat(YtDlpUpstreamProvider.readOutput(CompletableFuture.completedFuture("{}"), "stdout", 10))
                .isEqualTo("{}");
    }

    @Test
    void identifiesStreamWhenProcessOutputTimesOut() {
        assertThatThrownBy(() -> YtDlpUpstreamProvider.readOutput(new CompletableFuture<>(), "stdout", 10))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("yt-dlp stdout read timed out after process exit");
    }
}
