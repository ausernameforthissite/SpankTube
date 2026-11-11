package dev.spanktube.egress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class EgressProbeTest {

    @Test
    void retriesTransientProbeFailureBeforeMarkingEgressUnavailable() throws IOException {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generate_204", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.close();
            } else {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            MeterRegistry registry = new SimpleMeterRegistry();
            EgressProbe probe = probe(server, registry);

            assertThat(probe.probe().block(Duration.ofSeconds(5))).isTrue();
            assertThat(requests).hasValue(2);
            assertThat(registry.get("upstream_egress_ok").gauge().value()).isEqualTo(1.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishesSuccessfulGenerate204Probe() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generate_204", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            MeterRegistry registry = new SimpleMeterRegistry();
            EgressProbe probe = probe(server, registry);

            assertThat(probe.probe().block(Duration.ofSeconds(5))).isTrue();
            assertThat(registry.get("upstream_egress_ok").gauge().value()).isEqualTo(1.0);
        } finally {
            server.stop(0);
        }
    }

    private static EgressProbe probe(HttpServer server, MeterRegistry registry) {
        EgressProperties properties = new EgressProperties(EgressProperties.Mode.DIRECT, "", 1080,
                null, null, true, new EgressProperties.Probe(true, Duration.ofHours(1), Duration.ofSeconds(5),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate_204")));
        return new EgressProbe(WebClient.builder().build(), properties, registry);
    }
}
