package dev.spanktube.media;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
class ImageCache {

    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private final LinkedHashMap<String, CachedImage> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;

    synchronized CachedImage get(String url) {
        return entries.get(url);
    }

    synchronized void put(String url, byte[] body, MediaType contentType) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) {
            return;
        }
        CachedImage previous = entries.remove(url);
        if (previous != null) {
            bytes -= previous.body().length;
        }
        entries.put(url, new CachedImage(body, contentType));
        bytes += body.length;
        while (bytes > MAX_BYTES && entries.size() > 1) {
            Map.Entry<String, CachedImage> eldest = entries.entrySet().iterator().next();
            bytes -= eldest.getValue().body().length;
            entries.remove(eldest.getKey());
        }
    }

    record CachedImage(byte[] body, MediaType contentType) {
    }
}
