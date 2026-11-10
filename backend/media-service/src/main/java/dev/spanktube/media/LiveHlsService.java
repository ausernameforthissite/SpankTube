package dev.spanktube.media;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
class LiveHlsService {

    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");
    private final WebClient youtubeClient;

    LiveHlsService(@Qualifier("youtubeWebClient") WebClient youtubeClient) {
        this.youtubeClient = youtubeClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
    }

    Mono<String> manifest(String videoId, URI source) {
        return manifest(videoId, source, "");
    }

    Mono<String> manifest(String videoId, URI source, String kind) {
        URI validated = MediaUrlValidator.manifestUrl(source.toString());
        return youtubeClient.get()
                .uri(validated)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    return response.bodyToMono(String.class)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "Empty HLS playlist: " + response.statusCode() + " " + validated.getPath())))
                            .map(body -> {
                                if (body.isBlank()) {
                                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                            "Blank HLS playlist: " + response.statusCode() + " " + validated.getPath());
                                }
                                org.slf4j.LoggerFactory.getLogger(LiveHlsService.class).debug(
                                        "HLS playlist for {} returned {} bytes", videoId, body.length());
                                return rewrite(videoId, validated, body, kind);
                            });
                })
                .retryWhen(MediaRetry.transientFailures());
    }

    static String rewrite(String videoId, URI source, String playlist) {
        return rewrite(videoId, source, playlist, "");
    }

    static String rewrite(String videoId, URI source, String playlist, String kind) {
        StringBuilder result = new StringBuilder();
        String segmentKind = playlist.contains("#EXT-X-MAP:") ? "" : kind;
        boolean variant = false;
        for (String line : playlist.split("\\n", -1)) {
            String value = line.strip();
            if (value.isEmpty()) {
                result.append(line);
            } else if (value.startsWith("#")) {
                if (value.startsWith("#EXT-X-STREAM-INF:")) {
                    variant = true;
                }
                result.append(rewriteAttributes(videoId, source, value, kind));
            } else {
                boolean nestedPlaylist = variant || value.endsWith(".m3u8") || value.contains(".m3u8?");
                result.append(proxy(videoId, source, value, nestedPlaylist, segmentKind));
                variant = false;
            }
            result.append('\n');
        }
        if (result.length() > 0) {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private static String rewriteAttributes(String videoId, URI source, String line, String kind) {
        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        StringBuilder result = new StringBuilder();
        String resourceKind = line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO") ? "audio" : kind;
        if (line.startsWith("#EXT-X-MAP:")) {
            resourceKind = "";
        }
        int previous = 0;
        while (matcher.find()) {
            result.append(line, previous, matcher.start());
            boolean segment = line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")
                    || line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-PART:")
                    || line.startsWith("#EXT-X-PRELOAD-HINT:");
            result.append("URI=\"").append(proxy(videoId, source, matcher.group(1), !segment, resourceKind)).append('"');
            previous = matcher.end();
        }
        return result.append(line, previous, line.length()).toString();
    }

    private static String proxy(String videoId, URI source, String target, boolean playlist, String kind) {
        try {
            URI resolved = MediaUrlValidator.manifestUrl(source.resolve(target).toString());
            String suffix = "audio".equals(kind) ? ".aac" : extension(resolved);
            String path = playlist ? "/live/hls/playlist" : "/live/hls/segment" + suffix;
            return "/media/v/" + videoId + path + "?url="
                    + URLEncoder.encode(resolved.toString(), StandardCharsets.UTF_8).replace("+", "%20")
                    + ("audio".equals(kind) ? "&kind=audio" : "");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid live HLS URL", error);
        }
    }

    private static String extension(URI source) {
        Matcher matcher = Pattern.compile("\\.[A-Za-z0-9]{1,8}$").matcher(source.getPath());
        return matcher.find() ? matcher.group() : "";
    }
}
