package dev.spanktube.egress;

import java.net.URI;
import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.resolver.NoopAddressResolverGroup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

@AutoConfiguration
@EnableConfigurationProperties(EgressProperties.class)
public class EgressAutoConfiguration {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(EgressAutoConfiguration.class);

    @Bean(name = "youtubeHttpClient")
    @ConditionalOnMissingBean(name = "youtubeHttpClient")
    HttpClient youtubeHttpClient(EgressProperties properties) {
        HttpClient client = HttpClient.create().metrics(true, uri -> "/")
                .doOnRequest((request, connection) -> LOGGER.debug("Egress request host={} connectionHeader={} id={}",
                        URI.create(request.resourceUrl()).getHost(), request.requestHeaders().get(HttpHeaderNames.CONNECTION),
                        connection.channel().id()))
                .doAfterResponseSuccess((response, connection) -> LOGGER.debug(
                        "Egress response status={} connectionHeader={} persistent={} open={} disposed={} id={}",
                        response.status().code(), response.responseHeaders().get(HttpHeaderNames.CONNECTION),
                        connection.isPersistent(), connection.channel().isOpen(), connection.isDisposed(),
                        connection.channel().id()))
                .followRedirect(EgressAutoConfiguration::allowedRedirect);
        if (properties.mode() == EgressProperties.Mode.DIRECT) {
            return client;
        }

        Assert.hasText(properties.host(), "spanktube.egress.host is required in socks5 mode");
        Assert.isTrue(properties.port() > 0 && properties.port() <= 65535,
                "spanktube.egress.port must be a valid TCP port");

        if (properties.remoteDns()) {
            client = client.resolver(NoopAddressResolverGroup.INSTANCE);
        }
        return client.proxy(proxy -> {
            var configuredProxy = proxy.type(ProxyProvider.Proxy.SOCKS5)
                    .host(properties.host())
                    .port(properties.port());
            if (properties.username() != null && !properties.username().isBlank()) {
                configuredProxy.username(properties.username()).password(username -> properties.password());
            }
        });
    }

    @Bean(name = "youtubeWebClient")
    @ConditionalOnMissingBean(name = "youtubeWebClient")
    WebClient youtubeWebClient(@Qualifier("youtubeHttpClient") HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "spanktube.egress.probe", name = "enabled", havingValue = "true", matchIfMissing = true)
    EgressProbe egressProbe(@Qualifier("youtubeWebClient") WebClient webClient,
            EgressProperties properties, MeterRegistry meterRegistry) {
        return new EgressProbe(webClient, properties, meterRegistry);
    }

    private static boolean allowedRedirect(reactor.netty.http.client.HttpClientRequest request,
            reactor.netty.http.client.HttpClientResponse response) {
        String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
        if (location == null) {
            return false;
        }
        try {
            URI target = URI.create(response.uri()).resolve(location);
            String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
            boolean youtube = host.equals("youtube.com") || host.endsWith(".youtube.com");
            boolean googleVideo = host.equals("googlevideo.com") || host.endsWith(".googlevideo.com");
            String parameters = target.getQuery() == null ? "" : java.util.Arrays.stream(target.getQuery().split("&"))
                    .map(part -> part.split("=", 2)[0]).collect(java.util.stream.Collectors.joining(","));
            LOGGER.debug("Egress redirect fromHost={} toHost={} status={} connection={} targetParameters={}",
                    URI.create(request.resourceUrl()).getHost(), host, response.status().code(),
                    response.responseHeaders().get(HttpHeaderNames.CONNECTION), parameters);
            return "https".equals(target.getScheme()) && (youtube || googleVideo);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
