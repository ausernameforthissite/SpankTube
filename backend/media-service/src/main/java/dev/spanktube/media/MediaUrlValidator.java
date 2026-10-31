package dev.spanktube.media;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class MediaUrlValidator {

    private MediaUrlValidator() {
    }

    static URI mediaUrl(String value) {
        return allowedUrl(value, false);
    }

    static URI subtitleUrl(String value) {
        return allowedUrl(value, true);
    }

    private static URI allowedUrl(String value, boolean allowYoutube) {
        URI uri = URI.create(value);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean googleVideo = host.equals("googlevideo.com") || host.endsWith(".googlevideo.com");
        boolean youtube = host.equals("youtube.com") || host.endsWith(".youtube.com");
        if (!"https".equals(uri.getScheme()) || (!googleVideo && !(allowYoutube && youtube))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream returned a disallowed media URL");
        }
        return uri;
    }
}
