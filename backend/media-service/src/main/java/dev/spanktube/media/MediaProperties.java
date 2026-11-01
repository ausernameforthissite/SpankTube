package dev.spanktube.media;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.media")
public record MediaProperties(URI upstreamUrl, URI catalogUrl) {

    public MediaProperties {
        if (upstreamUrl == null) {
            upstreamUrl = URI.create("http://localhost:8081");
        }
        if (catalogUrl == null) {
            catalogUrl = URI.create("http://localhost:8084");
        }
    }
}
