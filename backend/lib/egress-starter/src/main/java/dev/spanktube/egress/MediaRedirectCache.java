package dev.spanktube.egress;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import reactor.netty.http.client.HttpClientResponse;

public class MediaRedirectCache {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<URI, Entry> redirects = new ConcurrentHashMap<>();

    public void record(HttpClientResponse response) {
        if (response.status().code() / 100 != 2 || response.resourceUrl() == null) {
            return;
        }
        URI target = validated(response.resourceUrl());
        String[] sources = response.redirectedFrom();
        if (target == null || sources == null || sources.length == 0) {
            return;
        }
        for (String source : sources) {
            URI original = validated(source);
            if (original != null) {
                redirects.put(original, new Entry(target, System.nanoTime() + TTL.toNanos()));
            }
        }
        while (redirects.size() > MAX_ENTRIES) {
            redirects.remove(redirects.keySet().iterator().next());
        }
    }

    public URI resolve(URI source) {
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

    public void invalidate(URI target) {
        redirects.entrySet().removeIf(entry -> entry.getValue().target().equals(target));
    }

    private static URI validated(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean googleVideo = host.equals("googlevideo.com") || host.endsWith(".googlevideo.com");
        boolean youtube = host.equals("youtube.com") || host.endsWith(".youtube.com");
        return "https".equals(uri.getScheme()) && (googleVideo || youtube) ? uri : null;
    }

    private record Entry(URI target, long expiresAt) {
    }
}
