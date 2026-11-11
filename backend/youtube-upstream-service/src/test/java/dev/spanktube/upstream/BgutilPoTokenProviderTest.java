package dev.spanktube.upstream;

import java.util.concurrent.atomic.AtomicReference;

import dev.spanktube.egress.EgressProperties;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgutilPoTokenProviderTest {

    @Test
    void returnsWebTokenAndPassesVideoAndEgressToProvider() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        var server = HttpServer.create().port(0)
                .handle((request, response) -> request.receive().aggregate().asString()
                        .doOnNext(requestBody::set)
                        .then(response.addHeader("Content-Type", "application/json").sendString(Mono.just("{\"poToken\":\"token-value\"}")).then()))
                .bindNow();
        try {
            EgressProperties egress = new EgressProperties(EgressProperties.Mode.SOCKS5,
                    "proxy", 1080, "user", "pass", true, null);
            BgutilPoTokenProvider provider = new BgutilPoTokenProvider(
                    "http://127.0.0.1:" + server.port(), egress, () -> "visitor-data");

            PoTokenResult result = provider.getWebClientPoToken("video-id");

            assertThat(result.visitorData).isEqualTo("visitor-data");
            assertThat(result.playerRequestPoToken).isEqualTo("token-value");
            assertThat(result.streamingDataPoToken).isEqualTo("token-value");
            var json = new ObjectMapper().readTree(requestBody.get());
            assertThat(json.path("content_binding").asText()).isEqualTo("video-id");
            assertThat(json.path("proxy").asText()).isEqualTo("socks5h://user:pass@proxy:1080");
            assertThat(json.path("bypass_cache").asBoolean()).isFalse();
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void doesNotInventTokenWhenProviderReturnsError() {
        var server = HttpServer.create().port(0)
                .handle((request, response) -> response.addHeader("Content-Type", "application/json").sendString(Mono.just("{\"error\":\"generation failed\"}")))
                .bindNow();
        try {
            BgutilPoTokenProvider provider = new BgutilPoTokenProvider("http://127.0.0.1:" + server.port(),
                    new EgressProperties(EgressProperties.Mode.DIRECT, "", 1080, "", "", true, null),
                    () -> "visitor-data");

            assertThatThrownBy(() -> provider.getWebClientPoToken("video-id"))
                    .isInstanceOfSatisfying(UpstreamException.class,
                            error -> assertThat(error.getMessage()).contains("generation failed"));
        } finally {
            server.disposeNow();
        }
    }
}
