package dev.spanktube.upstream;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class MediaLinks {

    private MediaLinks() {
    }

    static String thumbnail(String remoteUrl, String fallback) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return fallback;
        }
        return "/media/image?url=" + URLEncoder.encode(remoteUrl, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
