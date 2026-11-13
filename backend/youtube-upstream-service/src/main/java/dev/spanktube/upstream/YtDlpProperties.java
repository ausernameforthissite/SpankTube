package dev.spanktube.upstream;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.yt-dlp")
public record YtDlpProperties(String executable, Duration timeout, List<String> extraArgs, String cookiesFile) {

    public YtDlpProperties {
        if (executable == null || executable.isBlank()) {
            executable = "yt-dlp";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (extraArgs == null) {
            extraArgs = List.of();
        }
        cookiesFile = cookiesFile == null ? "" : cookiesFile;
    }
}
