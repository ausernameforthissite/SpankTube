package dev.spanktube.upstream;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Page;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NewPipePageCodecTest {

    private final NewPipePageCodec codec = new NewPipePageCodec(new ObjectMapper());

    @Test
    void roundTripsContinuationPage() {
        Page original = new Page("https://www.youtube.com/continuation", "id", List.of("item"),
                Map.of("cookie", "value"), new byte[] { 1, 2, 3 });

        Page decoded = codec.decode(codec.encode(original));

        assertThat(decoded.getUrl()).isEqualTo(original.getUrl());
        assertThat(decoded.getId()).isEqualTo(original.getId());
        assertThat(decoded.getIds()).isEqualTo(original.getIds());
        assertThat(decoded.getCookies()).isEqualTo(original.getCookies());
        assertThat(decoded.getBody()).isEqualTo(original.getBody());
    }

    @Test
    void treatsInitialCursorAsEmpty() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("0")).isNull();
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("np:not-base64"));
    }
}
