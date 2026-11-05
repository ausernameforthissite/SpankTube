package dev.spanktube.egress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EgressAutoConfiguration.class))
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues(
                            "spanktube.egress.mode=direct",
                            "spanktube.egress.probe.uri=http://127.0.0.1:" + server.getAddress().getPort()
                                    + "/generate_204",
                            "spanktube.egress.probe.interval=1h")
                    .run(context -> {
                        EgressProbe probe = context.getBean(EgressProbe.class);
                        assertThat(probe.probe().block(Duration.ofSeconds(5))).isTrue();
                        assertThat(requests).hasValue(2);
                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        assertThat(registry.get("upstream_egress_ok").gauge().value()).isEqualTo(1.0);
                    });
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
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EgressAutoConfiguration.class))
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues(
                            "spanktube.egress.mode=direct",
                            "spanktube.egress.probe.uri=http://127.0.0.1:" + server.getAddress().getPort()
                                    + "/generate_204",
                            "spanktube.egress.probe.interval=1h")
                    .run(context -> {
                        EgressProbe probe = context.getBean(EgressProbe.class);
                        assertThat(probe.probe().block(Duration.ofSeconds(5))).isTrue();
                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        assertThat(registry.get("upstream_egress_ok").gauge().value()).isEqualTo(1.0);
                    });
        } finally {
            server.stop(0);
        }
    }
}
