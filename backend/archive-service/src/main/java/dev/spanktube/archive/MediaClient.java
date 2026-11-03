package dev.spanktube.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MediaClient {

    private static final int CHUNK_SIZE = 8 * 1024 * 1024;
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("/(\\d+|\\*)$");

    private final HttpClient client;
    private final String baseUrl;
    private final int chunkSize;

    @Autowired
    MediaClient(@Value("${spanktube.media.url:http://localhost:8082}") String mediaUrl) {
        this(mediaUrl, CHUNK_SIZE);
    }

    MediaClient(String mediaUrl, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        this.chunkSize = chunkSize;
        baseUrl = mediaUrl.replaceAll("/$", "");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    long downloadTrack(String videoId, String formatId, Path destination, long estimatedBytes,
            ArchiveModels.Stage stage, DownloadProgress.Tracker tracker,
            Consumer<DownloadProgress.Snapshot> progress) {
        long downloaded = 0;
        long total = Math.max(0, estimatedBytes);
        long started = System.nanoTime();
        boolean append = false;
        try {
            while (total == 0 || downloaded < total) {
                long end = total > 0 ? Math.min(downloaded + chunkSize, total) - 1 : downloaded + chunkSize - 1;
                HttpResponse<InputStream> response = request(baseUrl + "/media/v/" + videoId + "/seg/" + formatId,
                        "bytes=" + downloaded + "-" + end);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw failure(response);
                }
                if (response.statusCode() == 200 && downloaded > 0) {
                    throw new ArchiveDownloadException("Server ignored Range after a partial download");
                }
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(0);
                long rangeTotal = rangeTotal(response.headers().firstValue("Content-Range").orElse(""));
                if (rangeTotal > 0) {
                    total = rangeTotal;
                } else if (response.statusCode() == 200 && contentLength > 0) {
                    total = contentLength;
                } else if (response.statusCode() == 206) {
                    total = 0;
                }
                try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(destination,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    input.transferTo(output);
                }
                long next = contentLength > 0 ? downloaded + contentLength : Files.size(destination);
                if (next <= downloaded) {
                    throw new ArchiveDownloadException("Empty media chunk before the expected end of stream");
                }
                downloaded = next;
                append = true;
                long speed = Math.round(downloaded / Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0));
                progress.accept(tracker.stage(stage, downloaded, total, speed));
                if (response.statusCode() == 200 || rangeTotal < 0 && contentLength < chunkSize) {
                    break;
                }
            }
            return downloaded;
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to download media track: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ArchiveDownloadException("Interrupted while downloading media track", error);
        }
    }

    long downloadResource(String path, Path destination) {
        try {
            HttpResponse<InputStream> response = request(baseUrl + path, null);
            if (response.statusCode() != 200) {
                throw failure(response);
            }
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(destination,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                return input.transferTo(output);
            }
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to download media resource: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ArchiveDownloadException("Interrupted while downloading media resource", error);
        }
    }

    private HttpResponse<InputStream> request(String url, String range) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET();
        if (range != null) {
            builder.header("Range", range);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static ArchiveDownloadException failure(HttpResponse<InputStream> response) throws IOException {
        String body;
        try (InputStream input = response.body()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new ArchiveDownloadException(body.isBlank() ? "Media-service returned HTTP " + response.statusCode() : body);
    }

    private static long rangeTotal(String contentRange) {
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(contentRange);
        if (!matcher.find() || "*".equals(matcher.group(1))) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

}
