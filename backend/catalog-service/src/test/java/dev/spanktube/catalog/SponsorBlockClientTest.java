package dev.spanktube.catalog;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorBlockClientTest {

    @Test
    void convertsSponsorBlockRangesToPlaybackSegments() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skipSegments", exchange -> {
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
        } finally {
            server.stop(0);
        }
    }
}
