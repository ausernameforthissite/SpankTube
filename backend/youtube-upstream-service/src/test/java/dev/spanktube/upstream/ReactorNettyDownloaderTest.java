package dev.spanktube.upstream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorNettyDownloaderTest {

    @Test
    void returnsAbsoluteLatestUrlAndAcceptsLargeHeaders() throws Exception {
        var server = HttpServer.create().port(0)
                .handle((request, response) -> response
                        .addHeader("X-YouTube-Cookies", "x".repeat(9_000))
                        .sendString(Mono.just("{}")))
                .bindNow();
        try {
            String url = "http://127.0.0.1:" + server.port() + "/youtubei/v1/browse";
            Request request = new Request("GET", url, Map.of(), null, null, false);

            Response response = new ReactorNettyDownloader(HttpClient.create()).execute(request);

            assertThat(response.responseCode()).isEqualTo(200);
            assertThat(response.responseBody()).isEqualTo("{}");
            assertThat(response.latestUrl()).isEqualTo(url);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void mergesAccountAndConsentCookies(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("cookies.txt");
        Files.writeString(file, "127.0.0.1\tFALSE\t/\tFALSE\t0\tSID\tsecret\n");
        var server = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just(request.requestHeaders().get("Cookie", ""))))
                .bindNow();
        try {
            String url = "http://127.0.0.1:" + server.port() + "/youtubei/v1/player";
            Request request = new Request("GET", url, Map.of("Cookie", java.util.List.of("SOCS=CAE")), null, null, false);

            Response response = new ReactorNettyDownloader(HttpClient.create(), new YouTubeCookieStore(file))
                    .execute(request);

            assertThat(response.responseBody()).isEqualTo("SOCS=CAE; SID=secret");
        } finally {
            server.disposeNow();
        }
    }
}
