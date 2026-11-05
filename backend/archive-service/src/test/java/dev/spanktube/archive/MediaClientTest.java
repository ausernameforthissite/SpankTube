package dev.spanktube.archive;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaClientTest {

    @TempDir
    Path directory;

    @Test
    void downloadsAndJoinsRanges() throws Exception {
        List<String> ranges = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media/v/video/seg/137", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            if ("bytes=0-3".equals(range)) {
                respond(exchange, 206, "abcd", "bytes 0-3/8");
            } else if ("bytes=4-7".equals(range)) {
                respond(exchange, 206, "efgh", "bytes 4-7/8");
            } else {
                respond(exchange, 416, "invalid range", null);
            }
        });
        server.start();
        try {
            MediaClient client = new MediaClient("http://127.0.0.1:" + server.getAddress().getPort(), 4);
            Path output = directory.resolve("track.mp4");

            long downloaded = client.downloadTrack("video", "137", output, 8,
                    ArchiveModels.Stage.DOWNLOADING_VIDEO, new DownloadProgress.Tracker(), progress -> { });

            assertThat(downloaded).isEqualTo(8);
            assertThat(Files.readString(output)).isEqualTo("abcdefgh");
            assertThat(ranges).containsExactly("bytes=0-3", "bytes=4-7");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTemporaryFailureWithoutRepeatingCompletedRanges() throws Exception {
        List<String> ranges = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media/v/video/seg/18", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            if (ranges.size() == 1) {
                respond(exchange, 503, "temporary", null);
            } else if ("bytes=0-3".equals(range)) {
                respond(exchange, 206, "abcd", "bytes 0-3/8");
            } else if ("bytes=4-7".equals(range)) {
                respond(exchange, 206, "efgh", "bytes 4-7/8");
            } else {
                respond(exchange, 416, "invalid range", null);
            }
        });
        server.start();
        try {
            MediaClient client = new MediaClient("http://127.0.0.1:" + server.getAddress().getPort(), 4);
            Path output = directory.resolve("track.mp4");

            long downloaded = client.downloadTrack("video", "18", output, 8,
                    ArchiveModels.Stage.DOWNLOADING_VIDEO, new DownloadProgress.Tracker(), progress -> { });

            assertThat(downloaded).isEqualTo(8);
            assertThat(Files.readString(output)).isEqualTo("abcdefgh");
            assertThat(ranges).containsExactly("bytes=0-3", "bytes=0-3", "bytes=4-7");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discardsPartialChunkBeforeRetry() throws Exception {
        List<String> ranges = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media/v/video/seg/18", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            if ("bytes=4-7".equals(range)) {
                respond(exchange, 206, "efgh", "bytes 4-7/8");
                return;
            }
            exchange.getResponseHeaders().add("Content-Range", "bytes 0-3/8");
            exchange.sendResponseHeaders(206, 4);
            if (ranges.size() == 1) {
                exchange.getResponseBody().write("ab".getBytes(StandardCharsets.UTF_8));
            } else {
                exchange.getResponseBody().write("abcd".getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });
        server.start();
        try {
            MediaClient client = new MediaClient("http://127.0.0.1:" + server.getAddress().getPort(), 4);
            Path output = directory.resolve("track.mp4");

            long downloaded = client.downloadTrack("video", "18", output, 8,
                    ArchiveModels.Stage.DOWNLOADING_VIDEO, new DownloadProgress.Tracker(), progress -> { });

            assertThat(downloaded).isEqualTo(8);
            assertThat(Files.readString(output)).isEqualTo("abcdefgh");
            assertThat(ranges).containsExactly("bytes=0-3", "bytes=0-3", "bytes=4-7");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesUpstreamErrorBody() throws Exception {
        String body = "{\"code\":\"ANTIBOT\",\"message\":\"blocked\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media/v/video/sub/en", exchange -> respond(exchange, 502, body, null));
        server.start();
        try {
            MediaClient client = new MediaClient("http://127.0.0.1:" + server.getAddress().getPort());

            assertThatThrownBy(() -> client.downloadResource("/media/v/video/sub/en", directory.resolve("en.vtt")))
                    .isInstanceOf(ArchiveDownloadException.class)
                    .hasMessage(body);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentRange) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentRange != null) {
            exchange.getResponseHeaders().add("Content-Range", contentRange);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
