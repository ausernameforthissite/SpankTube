package dev.spanktube.upstream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

final class ReactorNettyDownloader extends Downloader {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0";
    private final HttpClient httpClient;

    ReactorNettyDownloader(HttpClient httpClient) {
        this.httpClient = httpClient.httpResponseDecoder(spec -> spec.maxHeaderSize(64 * 1024));
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String url = request.url();
        if (url.startsWith("/") && !url.startsWith("//")) {
            url = "https://www.youtube.com" + url;
        }
        final String requestedUrl = url;
        try {
            Response response = httpClient.headers(headers -> {
                        if (!headers.contains("User-Agent")) {
                            headers.set("User-Agent", USER_AGENT);
                        }
                        request.headers().forEach((name, values) -> headers.add(name, values));
                    })
                    .request(io.netty.handler.codec.http.HttpMethod.valueOf(request.httpMethod()))
                    .uri(requestedUrl)
                    .send((httpRequest, outbound) -> request.dataToSend() == null ? outbound
                            : outbound.sendByteArray(Mono.just(request.dataToSend())))
                    .responseSingle((httpResponse, body) -> body.asString(StandardCharsets.UTF_8)
                            .defaultIfEmpty("")
                            .map(text -> new Response(httpResponse.status().code(),
                                    httpResponse.status().reasonPhrase(), headers(httpResponse), text,
                                    java.net.URI.create(requestedUrl).resolve(httpResponse.uri()).toString())))
                    .block(REQUEST_TIMEOUT);
            if (response == null) {
                throw new IOException("Empty response from YouTube");
            }
            if (response.responseCode() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", requestedUrl);
            }
            return response;
        } catch (RuntimeException error) {
            throw new IOException("NewPipe HTTP request failed: " + error.getMessage(), error);
        }
    }

    private static Map<String, List<String>> headers(reactor.netty.http.client.HttpClientResponse response) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        response.responseHeaders().forEach(header ->
                result.computeIfAbsent(header.getKey(), ignored -> new ArrayList<>()).add(header.getValue()));
        return result;
    }
}
