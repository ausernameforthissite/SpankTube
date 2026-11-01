package dev.spanktube.archive;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.archive")
public record ArchiveProperties(String root, long minDelaySeconds, long maxDelaySeconds,
        Duration downloadTimeout, long workerDelayMs) {

    public ArchiveProperties {
        if (root == null || root.isBlank()) {
            root = "/archive";
        }
        if (minDelaySeconds < 0) {
            minDelaySeconds = 2;
        }
        if (maxDelaySeconds < minDelaySeconds) {
            maxDelaySeconds = Math.max(5, minDelaySeconds);
        }
        if (downloadTimeout == null) {
            downloadTimeout = Duration.ofHours(1);
        }
        if (workerDelayMs <= 0) {
            workerDelayMs = 2000;
        }
    }
}
