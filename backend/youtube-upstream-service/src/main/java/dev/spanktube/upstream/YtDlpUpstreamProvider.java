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
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Invalid videoId");
        }
        VideoDetails details = parser.parse(execute(List.of("--no-playlist", "https://www.youtube.com/watch?v=" + videoId)));
        if (details.channelId() == null || details.channelId().isBlank()) {
            return details;
        }
        try {
            ChannelDetails channel = channel(details.channelId(), "0");
            return new VideoDetails(details.videoId(), details.title(), details.description(), details.channelId(),
                    details.channelName(), details.channelHandle(), details.channelCustomPath(), channel.avatarUrl(),
                    channel.subscriberCount(), details.durationSeconds(), details.viewCount(), details.likeCount(),
                    details.publishedAt(), details.thumbnailUrl(), details.formats(), details.subtitles());
        } catch (UpstreamException error) {
            return details;
        }
    }

    @Override
    public SearchResults search(String query, String type, String cursor) {
        if (query == null || query.isBlank() || query.length() > 200) {
            throw new IllegalArgumentException("Search query must contain 1-200 characters");
        }
        int offset = page(cursor);
        String target = switch (type == null ? "videos" : type) {
            case "videos" -> "ytsearch20:" + query;
            case "channels" -> "https://www.youtube.com/results?search_query=" + encode(query) + "&sp=EgIQAg%3D%3D";
            case "playlists" -> "https://www.youtube.com/results?search_query=" + encode(query) + "&sp=EgIQAw%3D%3D";
            default -> throw new IllegalArgumentException("Unknown search type");
        };
        List<String> arguments = new ArrayList<>(pageArguments(offset));
        arguments.addAll(List.of("--flat-playlist", target));
        SearchResults results = parser.search(execute(arguments));
        return new SearchResults(results.items(), nextCursor(results.items(), offset));
    }

    @Override
    public ChannelDetails channel(String channelId, String cursor) {
        int offset = page(cursor);
        List<String> arguments = new ArrayList<>(pageArguments(offset));
        String baseUrl = "https://www.youtube.com/" + channelPath(channelId);
        arguments.addAll(List.of("--flat-playlist", baseUrl + "/videos"));
        ChannelDetails channel;
        try {
            channel = parser.channel(execute(arguments));
        } catch (UpstreamException error) {
            if (!error.getMessage().contains("does not have a videos tab")) {
                throw error;
            }
            arguments.set(arguments.size() - 1, baseUrl);
            channel = parser.channel(execute(arguments));
        }
        return new ChannelDetails(channel.channelId(), channel.title(), channel.handle(), channel.customPath(),
                channel.avatarUrl(), channel.subscriberCount(), channel.videos(), channel.playlists(),
                nextCursor(channel.videos(), offset));
    }

    @Override
    public SearchResults channelVideos(String channelId, String cursor) {
        List<String> arguments = new ArrayList<>();
        int offset = 0;
        if (cursor != null) {
            offset = page(cursor);
            arguments.addAll(pageArguments(offset));
        }
        arguments.addAll(List.of("--flat-playlist", "https://www.youtube.com/" + channelPath(channelId) + "/videos"));
        try {
            SearchResults results = parser.search(execute(arguments));
            return new SearchResults(results.items(), cursor == null ? null : nextCursor(results.items(), offset));
        } catch (UpstreamException error) {
            if (!error.getMessage().contains("does not have a videos tab")) {
                throw error;
            }
            ChannelDetails channel = channel(channelId, cursor);
            return new SearchResults(channel.videos(), channel.nextCursor());
        }
    }

    @Override
    public SearchResults channelPlaylists(String channelId, String cursor) {
        int offset = page(cursor);
        List<String> arguments = new ArrayList<>(pageArguments(offset));
        arguments.addAll(List.of("--flat-playlist", "https://www.youtube.com/" + channelPath(channelId) + "/playlists"));
        try {
            SearchResults results = parser.search(execute(arguments));
            return new SearchResults(results.items(), nextCursor(results.items(), offset));
        } catch (UpstreamException error) {
            if (error.getMessage().contains("does not have a playlists tab")) {
                return new SearchResults(List.of(), null);
            }
            throw error;
        }
    }

    @Override
    public PlaylistDetails playlist(String playlistId, String cursor) {
        if (playlistId == null || !playlistId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid playlistId");
        }
        List<String> arguments = new ArrayList<>();
        int offset = 0;
        if (cursor != null) {
            offset = page(cursor);
            arguments.addAll(pageArguments(offset));
        }
        arguments.addAll(List.of("--flat-playlist", "https://www.youtube.com/playlist?list=" + playlistId));
        PlaylistDetails playlist = parser.playlist(execute(arguments));
        return new PlaylistDetails(playlist.playlistId(), playlist.title(), playlist.channelId(), playlist.channelName(),
                playlist.channelAvatarUrl(), playlist.channelHandle(), playlist.channelCustomPath(),
                playlist.channelSubscriberCount(), playlist.items(),
                cursor == null ? null : nextCursor(playlist.items(), offset), playlist.thumbnailUrl());
    }

    private static String channelPath(String channelId) {
        if (channelId == null || !channelId.matches("@?[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid channelId");
        }
        return channelId.startsWith("@") ? channelId
                : channelId.startsWith("UC") ? "channel/" + channelId : "c/" + channelId;
    }

    private static int page(String cursor) {
        try {
            int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
            if (offset < 0 || offset > 1_000_000) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            return offset;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid cursor", error);
        }
    }

    private static List<String> pageArguments(int offset) {
        return List.of("--playlist-start", Integer.toString(offset + 1),
                "--playlist-end", Integer.toString(offset + 20));
    }

    private static String nextCursor(List<?> items, int offset) {
        return items.size() == 20 ? Integer.toString(offset + items.size()) : null;
    }

    private String execute(List<String> additionalArguments) {
        requireEgress();
        List<String> command = new ArrayList<>();
        command.add(properties.executable());
        command.addAll(List.of(
                "--dump-single-json",
                "--skip-download",
                "--no-warnings",
                "--no-color",
                "--socket-timeout", "20",
                "--retries", "1",
                "--extractor-retries", "1"));
        if (egressProperties.mode() == EgressProperties.Mode.SOCKS5) {
            command.add("--proxy");
            command.add(proxyUrl());
        }
        command.addAll(properties.extraArgs());
        command.addAll(additionalArguments);

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
            return stdout.get(2, TimeUnit.SECONDS);
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

    private void requireEgress() {
        if (egressProbe != null && !egressProbe.isAvailable()) {
            throw UpstreamException.unavailable("YouTube egress is unavailable: " + egressProbe.lastError());
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
