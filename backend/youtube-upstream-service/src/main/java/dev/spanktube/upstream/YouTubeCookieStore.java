package dev.spanktube.upstream;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class YouTubeCookieStore {

    private final List<Entry> entries;

    @Autowired
    YouTubeCookieStore(@Value("${YOUTUBE_COOKIES_FILE:}") String path) throws IOException {
        this(path == null || path.isBlank() ? null : Path.of(path));
    }

    YouTubeCookieStore(Path path) throws IOException {
        entries = path == null ? List.of() : read(path);
    }

    Optional<String> cookieHeader(URI uri) {
        if (entries.isEmpty() || uri == null || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        long now = Instant.now().getEpochSecond();
        String value = entries.stream()
                .filter(entry -> entry.matches(host, path, secure, now))
                .map(entry -> entry.name + "=" + entry.value)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static List<Entry> read(Path path) throws IOException {
        List<Entry> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#HttpOnly_")) {
                    line = line.substring("#HttpOnly_".length());
                } else if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 7) {
                    continue;
                }
                try {
                    result.add(new Entry(fields[0], Boolean.parseBoolean(fields[1]), fields[2],
                            Boolean.parseBoolean(fields[3]), Long.parseLong(fields[4]), fields[5], fields[6]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (result.isEmpty()) {
            throw new IOException("No valid Netscape cookies found in " + path);
        }
        return List.copyOf(result);
    }

    private record Entry(String domain, boolean includeSubdomains, String path, boolean secure,
            long expiresAt, String name, String value) {

        boolean matches(String host, String requestPath, boolean https, long now) {
            String normalized = domain.startsWith(".") ? domain.substring(1) : domain;
            normalized = normalized.toLowerCase(Locale.ROOT);
            boolean domainMatches = includeSubdomains
                    ? host.equals(normalized) || host.endsWith("." + normalized)
                    : host.equalsIgnoreCase(domain);
            return domainMatches && requestPath.startsWith(path) && (!secure || https)
                    && (expiresAt == 0 || expiresAt > now);
        }
    }
}
