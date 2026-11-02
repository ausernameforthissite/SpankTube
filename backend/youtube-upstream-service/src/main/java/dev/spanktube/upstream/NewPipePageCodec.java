package dev.spanktube.upstream;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.schabi.newpipe.extractor.Page;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class NewPipePageCodec {

    private static final String PREFIX = "np:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 512;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Entry> pages = new ConcurrentHashMap<>();

    NewPipePageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(Page page) {
        if (!Page.isValid(page)) {
            return null;
        }
        PageData data = new PageData(page.getUrl(), page.getId(), page.getIds(), page.getCookies(), page.getBody());
        String id = UUID.randomUUID().toString().replace("-", "");
        pages.put(id, new Entry(data, System.nanoTime()));
        cleanup();
        return PREFIX + id;
    }

    Page decode(String cursor) {
        if (cursor == null || cursor.isBlank() || "0".equals(cursor)) {
            return null;
        }
        if (!cursor.startsWith(PREFIX)) {
            throw UpstreamException.failed("Legacy pagination cursor requires yt-dlp");
        }
        String id = cursor.substring(PREFIX.length());
        if (id.length() > 64) {
            return decodeLegacy(id);
        }
        Entry entry = pages.get(id);
        if (entry == null || expired(entry, System.nanoTime())) {
            pages.remove(id);
            throw new IllegalArgumentException("Unknown or expired cursor");
        }
        return new Page(entry.data().url(), entry.data().id(), entry.data().ids(), entry.data().cookies(),
                entry.data().body());
    }

    private Page decodeLegacy(String encoded) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            PageData data = objectMapper.readValue(json, PageData.class);
            return new Page(data.url(), data.id(), data.ids(), data.cookies(), data.body());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid cursor", error);
        }
    }

    private void cleanup() {
        long now = System.nanoTime();
        pages.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        while (pages.size() > MAX_ENTRIES) {
            String eldest = pages.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().createdAt(), right.getValue().createdAt()))
                    .map(Map.Entry::getKey).orElse(null);
            if (eldest == null) {
                return;
            }
            pages.remove(eldest);
        }
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.createdAt() >= TTL.toNanos();
    }

    private record Entry(PageData data, long createdAt) {
    }

    private record PageData(String url, String id, List<String> ids, Map<String, String> cookies, byte[] body) {
    }
}
