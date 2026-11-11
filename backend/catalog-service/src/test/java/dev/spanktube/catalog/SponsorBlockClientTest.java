package dev.spanktube.catalog;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorBlockClientTest {

    @Test
    void convertsSponsorBlockRangesToPlaybackSegments() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    [{"category":"sponsor","segment":[12.5,18.75]},
                     {"category":"sponsor","segment":[1]}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SponsorBlockClient client = new SponsorBlockClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(client.segments("video")).containsExactly(
                    new SponsorBlockClient.Segment("sponsor", 12.5, 18.75));
            assertThat(query.get()).isEqualTo("videoID=video&categories=%5B%22sponsor%22%5D");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientServerErrorOnce() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, 0);
            } else {
                byte[] body = "[{\"category\":\"sponsor\",\"segment\":[2.0,4.0]}]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            SponsorBlockClient client = new SponsorBlockClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(client.segments("video")).containsExactly(
                    new SponsorBlockClient.Segment("sponsor", 2.0, 4.0));
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryNotFound() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });
        server.start();
        try {
            SponsorBlockClient client = new SponsorBlockClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(client.segments("video")).isEmpty();
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
