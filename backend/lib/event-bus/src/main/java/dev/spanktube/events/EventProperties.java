package dev.spanktube.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.events")
public record EventProperties(Boolean enabled, String exchange) {

    public EventProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (exchange == null || exchange.isBlank()) {
            exchange = "spanktube.events";
        }
    }
}
