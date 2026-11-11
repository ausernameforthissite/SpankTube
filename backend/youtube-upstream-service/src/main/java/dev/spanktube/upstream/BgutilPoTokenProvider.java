package dev.spanktube.upstream;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import dev.spanktube.egress.EgressProperties;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class BgutilPoTokenProvider implements PoTokenProvider {

    private final RestClient client;
    private final EgressProperties egressProperties;
    private final Supplier<String> visitorDataLoader;
    private volatile String visitorData;

    @Autowired
    BgutilPoTokenProvider(@Value("${spanktube.pot-provider.url:http://localhost:4416}") String baseUrl,
            EgressProperties egressProperties) {
        this(baseUrl, egressProperties, BgutilPoTokenProvider::loadVisitorData);
    }

    BgutilPoTokenProvider(String baseUrl, EgressProperties egressProperties, Supplier<String> visitorDataLoader) {
        var httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(25));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.egressProperties = egressProperties;
        this.visitorDataLoader = visitorDataLoader;
    }

    @Override
    public PoTokenResult getWebClientPoToken(String videoId) {
        String visitor = visitorData();
        Map<String, Object> request = new HashMap<>();
        request.put("content_binding", videoId);
        request.put("bypass_cache", false);
        request.put("disable_tls_verification", false);
        if (egressProperties.mode() == EgressProperties.Mode.SOCKS5) {
            request.put("proxy", proxyUrl());
        }
        Map<String, Object> response = client.post().uri("/get_pot").body(request).retrieve().body(Map.class);
        Object token = response == null ? null : response.get("poToken");
        if (!(token instanceof String value) || value.isBlank()) {
            Object error = response == null ? null : response.get("error");
            throw UpstreamException.failed("bgutil PO-token provider returned no token"
                    + (error == null ? "" : ": " + error));
        }
        return new PoTokenResult(visitor, value, value);
    }

    @Override
    public PoTokenResult getWebEmbedClientPoToken(String videoId) {
        return null;
    }

    @Override
    public PoTokenResult getAndroidClientPoToken(String videoId) {
        return null;
    }

    @Override
    public PoTokenResult getIosClientPoToken(String videoId) {
        return null;
    }

    @org.springframework.context.event.EventListener
    void warmUp(org.springframework.boot.context.event.ApplicationReadyEvent event) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            long started = System.nanoTime();
            try {
                getWebClientPoToken("3TYZjVzWazE");
                org.slf4j.LoggerFactory.getLogger(BgutilPoTokenProvider.class).info(
                        "bgutil PO-token provider warmed in {} ms", (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(BgutilPoTokenProvider.class).warn(
                        "bgutil PO-token warm-up failed: {}", error.getClass().getSimpleName());
            }
        });
    }

    private String visitorData() {
        String current = visitorData;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (visitorData == null) {
                visitorData = visitorDataLoader.get();
            }
            return visitorData;
        }
    }

    private static String loadVisitorData() {
        try {
            var requestInfo = InnertubeClientRequestInfo.ofWebClient();
            requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion();
            return YoutubeParsingHelper.getVisitorDataFromInnertube(requestInfo,
                    NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false);
        } catch (Exception error) {
            throw UpstreamException.failed("Unable to obtain visitor data for bgutil PO token: " + error.getMessage(), error);
        }
    }

    private String proxyUrl() {
        String scheme = egressProperties.remoteDns() ? "socks5h" : "socks5";
        StringBuilder credentials = new StringBuilder();
        if (egressProperties.username() != null && !egressProperties.username().isBlank()) {
            credentials.append(encode(egressProperties.username()));
            if (egressProperties.password() != null) {
                credentials.append(':').append(encode(egressProperties.password()));
            }
            credentials.append('@');
        }
        String host = egressProperties.host();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return scheme + "://" + credentials + host + ":" + egressProperties.port();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
