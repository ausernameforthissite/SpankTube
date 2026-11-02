package dev.spanktube.upstream;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.schabi.newpipe.extractor.Page;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class NewPipePageCodec {

    private static final String PREFIX = "np:";
    private final ObjectMapper objectMapper;

    NewPipePageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(Page page) {
        if (!Page.isValid(page)) {
            return null;
        }
        byte[] json = objectMapper.writeValueAsBytes(new PageData(page.getUrl(), page.getId(),
                page.getIds(), page.getCookies(), page.getBody()));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    Page decode(String cursor) {
        if (cursor == null || cursor.isBlank() || "0".equals(cursor)) {
            return null;
        }
        if (!cursor.startsWith(PREFIX)) {
            throw UpstreamException.failed("Legacy pagination cursor requires yt-dlp");
        }
        if (cursor.length() > 16_384) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length()));
            PageData data = objectMapper.readValue(json, PageData.class);
            return new Page(data.url(), data.id(), data.ids(), data.cookies(), data.body());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid cursor", error);
        }
    }

    private record PageData(String url, String id, List<String> ids, Map<String, String> cookies, byte[] body) {
    }
}
