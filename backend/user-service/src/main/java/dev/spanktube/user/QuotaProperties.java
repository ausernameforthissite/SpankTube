package dev.spanktube.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.quota")
public record QuotaProperties(long adminDefaultBytes, long viewerDefaultBytes, int lruThresholdPercent) {

    public QuotaProperties {
        if (adminDefaultBytes <= 0) {
            adminDefaultBytes = 50L * 1024 * 1024 * 1024;
        }
        if (viewerDefaultBytes <= 0) {
            viewerDefaultBytes = 10L * 1024 * 1024 * 1024;
        }
        if (lruThresholdPercent <= 0) {
            lruThresholdPercent = 90;
        }
    }
}
