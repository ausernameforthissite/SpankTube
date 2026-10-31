package dev.spanktube.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.spanktube.egress.EgressProbe;
import dev.spanktube.egress.EgressProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class YtDlpUpstreamProvider implements UpstreamProvider {

    private final YtDlpProperties properties;
    private final EgressProperties egressProperties;
    private final EgressProbe egressProbe;
    private final YtDlpResponseParser parser;

    YtDlpUpstreamProvider(YtDlpProperties properties, EgressProperties egressProperties,
            ObjectProvider<EgressProbe> egressProbe, YtDlpResponseParser parser) {
        this.properties = properties;
        this.egressProperties = egressProperties;
        this.egressProbe = egressProbe.getIfAvailable();
        this.parser = parser;
    }

    @Override
    public VideoDetails video(String videoId) {
        if (egressProbe != null && !egressProbe.isAvailable()) {
            throw UpstreamException.unavailable("YouTube egress is unavailable: " + egressProbe.lastError());
        }

        List<String> command = new ArrayList<>();
        command.add(properties.executable());
        command.addAll(List.of(
                "--dump-single-json",
                "--skip-download",
                "--no-warnings",
                "--no-color",
                "--no-playlist",
                "--socket-timeout", "20",
                "--retries", "1",
                "--extractor-retries", "1"));
        if (egressProperties.mode() == EgressProperties.Mode.SOCKS5) {
            command.add("--proxy");
            command.add(proxyUrl());
        }
        command.addAll(properties.extraArgs());
        command.add("https://www.youtube.com/watch?v=" + videoId);

        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw UpstreamException.failed("yt-dlp timed out after " + properties.timeout());
            }
            String errors = stderr.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                String message = sanitize(errors);
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("sign in to confirm") || normalized.contains("not a bot")
                        || normalized.contains("po token") || normalized.contains("po_token")
                        || normalized.contains("botguard")) {
                    throw UpstreamException.antiBot(message);
                }
                throw UpstreamException.failed("yt-dlp exited with code " + process.exitValue() + ": " + message);
            }
            return parser.parse(stdout.get(2, TimeUnit.SECONDS));
        } catch (IOException | ExecutionException | TimeoutException error) {
            throw UpstreamException.failed("Unable to execute yt-dlp: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw UpstreamException.failed("Interrupted while waiting for yt-dlp", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
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

    private String sanitize(String errorOutput) {
        String sanitized = errorOutput == null || errorOutput.isBlank() ? "No error output" : errorOutput.trim();
        if (egressProperties.password() != null && !egressProperties.password().isBlank()) {
            sanitized = sanitized.replace(egressProperties.password(), "***");
            sanitized = sanitized.replace(encode(egressProperties.password()), "***");
        }
        return sanitized.length() > 4000 ? sanitized.substring(0, 4000) : sanitized;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static CompletableFuture<String> readAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
    }
}
