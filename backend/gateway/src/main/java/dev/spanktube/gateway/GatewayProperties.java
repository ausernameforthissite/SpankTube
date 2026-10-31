package dev.spanktube.gateway;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.gateway")
public record GatewayProperties(URI userServiceUrl, URI upstreamServiceUrl, URI mediaServiceUrl,
        String jwtPublicKey, List<String> allowedOrigins) {

    public GatewayProperties {
        if (userServiceUrl == null) {
            userServiceUrl = URI.create("http://localhost:8083");
        }
        if (upstreamServiceUrl == null) {
            upstreamServiceUrl = URI.create("http://localhost:8081");
        }
        if (mediaServiceUrl == null) {
            mediaServiceUrl = URI.create("http://localhost:8082");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173");
        }
    }
}
