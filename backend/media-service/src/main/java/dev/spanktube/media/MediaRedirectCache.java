package dev.spanktube.media;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import reactor.netty.http.client.HttpClientResponse;

final class MediaRedirectCache {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<URI, Entry> redirects = new ConcurrentHashMap<>();

    void record(HttpClientResponse response) {
        if (response.status().code() / 100 != 2 || response.resourceUrl() == null) {
            return;
        }
        URI target = MediaUrlValidator.subtitleUrl(response.resourceUrl());
        String[] sources = response.redirectedFrom();
        if (sources == null) {
            return;
        }
        for (String source : sources) {
            URI original = MediaUrlValidator.subtitleUrl(source);
            redirects.put(original, new Entry(target, System.nanoTime() + TTL.toNanos()));
        }
        while (redirects.size() > MAX_ENTRIES) {
            redirects.remove(redirects.keySet().iterator().next());
        }
    }

    URI resolve(URI source) {
        URI current = source;
        for (int depth = 0; depth < 4; depth++) {
            Entry entry = redirects.get(current);
            if (entry == null) {
                return current;
            }
            if (entry.expiresAt() <= System.nanoTime()) {
                redirects.remove(current, entry);
                return source;
            }
            current = entry.target();
        }
        return source;
    }

    void invalidate(URI target) {
        redirects.entrySet().removeIf(entry -> entry.getValue().target().equals(target));
    }

    private record Entry(URI target, long expiresAt) {
    }
}
