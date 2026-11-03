package dev.spanktube.archive;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorBlockClientTest {

    @Test
    void requestsAllArchiveCategoriesAndParsesSegments() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "[{\"category\":\"sponsor\",\"segment\":[12.5,18.75]}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SponsorBlockClient client = new SponsorBlockClient("http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(client.segments("video")).containsExactly(
                    new SponsorBlockClient.Segment("sponsor", 12.5, 18.75));
            assertThat(query.get()).startsWith("videoID=video&categories=%5B%22sponsor%22");
            assertThat(query.get()).contains("filler");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsMissingSegmentsAsEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            SponsorBlockClient client = new SponsorBlockClient("http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(client.segments("video")).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
