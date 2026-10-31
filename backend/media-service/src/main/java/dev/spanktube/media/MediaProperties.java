package dev.spanktube.media;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.media")
public record MediaProperties(URI upstreamUrl) {

    public MediaProperties {
        if (upstreamUrl == null) {
            upstreamUrl = URI.create("http://localhost:8081");
        }
    }
}
