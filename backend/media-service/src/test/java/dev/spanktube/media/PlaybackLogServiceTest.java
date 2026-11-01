package dev.spanktube.media;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackLogServiceTest {

    @Test
    void streamsOnlyTenLatestLines() {
        PlaybackLogService service = new PlaybackLogService();
        var stream = service.stream("3TYZjVzWazE", "test-session");
        for (int index = 1; index <= 12; index++) {
            service.log("3TYZjVzWazE", "test-session", "line" + index);
        }

        List<String> lines = stream.take(10).map(event -> event.data()).collectList().block(Duration.ofSeconds(1));

        assertThat(lines).containsExactly("line3", "line4", "line5", "line6", "line7", "line8",
                "line9", "line10", "line11", "line12");
    }
}
