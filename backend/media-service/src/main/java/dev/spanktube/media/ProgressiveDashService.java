package dev.spanktube.media;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
class ProgressiveDashService {

    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private final WebClient youtubeClient;
    private final UpstreamClient upstreamClient;
    private final PlaybackLogService playbackLogs;
    private final Path cacheRoot;
    private final String ffmpegExecutable;
    private final ConcurrentHashMap<String, CachedPreparation> preparations = new ConcurrentHashMap<>();

    ProgressiveDashService(@Qualifier("youtubeWebClient") WebClient youtubeClient, UpstreamClient upstreamClient,
            PlaybackLogService playbackLogs,
            @Value("${spanktube.media.dash-cache-root:/tmp/spanktube-dash}") String cacheRoot,
            @Value("${spanktube.media.ffmpeg-executable:ffmpeg}") String ffmpegExecutable) {
        this.youtubeClient = youtubeClient;
        this.upstreamClient = upstreamClient;
        this.playbackLogs = playbackLogs;
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        this.ffmpegExecutable = ffmpegExecutable;
    }

    static boolean requiresRemux(UpstreamVideo video) {
        return video.formats().size() == 1 && video.formats().getFirst().hasVideo()
                && video.formats().getFirst().hasAudio() && video.formats().getFirst().indexRange() == null;
    }

    Mono<String> manifest(UpstreamVideo video, String playbackSession) {
        return prepare(video, playbackSession).map(path -> {
            try {
                return Files.readString(path.resolve("manifest.mpd"));
            } catch (IOException error) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read DASH manifest", error);
            }
        });
    }

    Mono<ResponseEntity<FileSystemResource>> file(String videoId, String filename) {
        if (!filename.matches("[A-Za-z0-9._-]+")) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return upstreamClient.video(videoId).flatMap(video -> prepare(video, null)).map(directory -> {
            Path remux = directory.resolve("remux");
            Path file = remux.resolve(filename).normalize();
            if (!file.startsWith(remux) || !Files.isRegularFile(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            MediaType contentType = filename.endsWith(".mpd")
                    ? MediaType.parseMediaType("application/dash+xml") : MediaType.parseMediaType("video/mp4");
            return ResponseEntity.ok().contentType(contentType).body(new FileSystemResource(file));
        });
    }

    private Mono<Path> prepare(UpstreamVideo video, String playbackSession) {
        if (!requiresRemux(video)) {
            return Mono.error(new IllegalArgumentException("A single progressive MP4 is required"));
        }
        String videoId = video.videoId();
        CachedPreparation cached = preparations.get(videoId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.task;
        }
        URI source = MediaUrlValidator.mediaUrl(video.formats().getFirst().url());
        Mono<Path> task = Mono.defer(() -> {
                    Path directory = cacheRoot.resolve(videoId).normalize();
                    Path input = directory.resolve("source.mp4");
                    Path manifest = directory.resolve("manifest.mpd");
                    try {
                        Files.createDirectories(directory);
                        if (isFresh(manifest)) {
                            return Mono.just(directory);
                        }
                    } catch (IOException error) {
                        return Mono.error(error);
                    }
                    playbackLogs.log(videoId, playbackSession, "Скачивание прогрессивного MP4 для DASH…");
                    return download(source, input)
                            .then(Mono.fromRunnable(() -> {
                                playbackLogs.log(videoId, playbackSession, "Создание DASH-фрагментов без перекодирования…");
                                remux(input, directory);
                            }))
                            .thenReturn(directory);
                })
                .doOnError(error -> {
                    preparations.remove(videoId);
                    playbackLogs.error(videoId, playbackSession, error);
                })
                .cache();
        CachedPreparation next = new CachedPreparation(task, Instant.now().plus(CACHE_TTL));
        preparations.put(videoId, next);
        return task;
    }

    private static final long DOWNLOAD_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("^bytes \\d+-\\d+/(\\d+|\\*)$");

    Mono<Void> download(URI source, Path destination) {
        return Mono.fromCallable(() -> {
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return Files.isRegularFile(destination) ? Files.size(destination) : 0L;
        }).flatMap(offset -> downloadChunk(source, destination, offset, null));
    }

    private Mono<Void> downloadChunk(URI source, Path destination, long offset, Long total) {
        if (total != null && offset >= total) {
            return offset == total ? Mono.empty() : Mono.error(new IOException("Progressive MP4 is larger than expected"));
        }
        long end = total == null ? offset + DOWNLOAD_CHUNK_SIZE - 1
                : Math.min(offset + DOWNLOAD_CHUNK_SIZE, total) - 1;
        return Mono.defer(() -> Mono.fromCallable(() -> {
                    try (FileChannel channel = FileChannel.open(destination,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        channel.truncate(offset);
                    }
                    return offset;
                }).then(requestChunk(source, destination, offset, end, total)))
                .timeout(Duration.ofMinutes(2))
                .retryWhen(MediaRetry.transientFailures())
                .flatMap(chunk -> {
                    if (chunk.downloaded() <= offset) {
                        return Mono.error(new IOException("Empty progressive MP4 chunk"));
                    }
                    if (chunk.total() != null && chunk.downloaded() > chunk.total()) {
                        return Mono.error(new IOException("Progressive MP4 chunk exceeds the declared size"));
                    }
                    return chunk.hasMore()
                            ? downloadChunk(source, destination, chunk.downloaded(), chunk.total()) : Mono.empty();
                });
    }

    private Mono<ChunkDownload> requestChunk(URI source, Path destination, long offset, long end, Long total) {
        return youtubeClient.get().uri(source)
                .header(org.springframework.http.HttpHeaders.RANGE, "bytes=" + offset + "-" + end)
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .flatMap(response -> {
                    if (response.getStatusCode().value() == 200 && offset > 0) {
                        return response.getBody().then(Mono.error(
                                new IOException("Server ignored Range after a partial progressive download")));
                    }
                    long contentLength = response.getHeaders().getContentLength();
                    return Mono.using(
                            () -> FileChannel.open(destination, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                                    .position(offset),
                            channel -> DataBufferUtils.write(response.getBody()
                                    .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()), channel).then(),
                            channel -> {
                                try {
                                    channel.close();
                                } catch (IOException error) {
                                    throw new java.io.UncheckedIOException(error);
                                }
                            })
                            .then(Mono.fromCallable(() -> {
                                long downloaded = Files.size(destination);
                                if (contentLength >= 0 && downloaded - offset != contentLength) {
                                    throw new java.io.EOFException("Incomplete progressive MP4 chunk");
                                }
                                Long totalBytes = contentRangeTotal(response.getHeaders()
                                        .getFirst(org.springframework.http.HttpHeaders.CONTENT_RANGE), total);
                                boolean hasMore = totalBytes != null ? downloaded < totalBytes
                                        : response.getStatusCode().value() == 206 && contentLength >= DOWNLOAD_CHUNK_SIZE;
                                return new ChunkDownload(downloaded, totalBytes, hasMore);
                            }));
                });
    }

    private static Long contentRangeTotal(String header, Long fallback) {
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(header == null ? "" : header);
        if (!matcher.matches() || "*".equals(matcher.group(1))) {
            return fallback;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private record ChunkDownload(long downloaded, Long total, boolean hasMore) {
    }

    void remux(Path input, Path directory) {
        Process process = null;
        try {
            Files.createDirectories(directory.resolve("remux"));
            process = new ProcessBuilder(ffmpegExecutable, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy",
                    "-f", "dash", "-seg_duration", "4", "-use_template", "1", "-use_timeline", "1",
                    "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                    "-init_seg_name", "remux/init-$RepresentationID$.m4s",
                    "-media_seg_name", "remux/chunk-$RepresentationID$-$Number%05d$.m4s",
                    directory.resolve("manifest.mpd").toString())
                    .redirectErrorStream(true).start();
            final Process running = process;
            var outputReader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var stream = running.getInputStream()) {
                    return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException error) {
                    return error.getMessage();
                }
            });
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("DASH remux timed out");
            }
            String output = outputReader.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg failed: " + output.substring(0, Math.min(4000, output.length())));
            }
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to remux progressive MP4", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DASH remux interrupted", error);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isFresh(Path manifest) throws IOException {
        return Files.isRegularFile(manifest) && Files.getLastModifiedTime(manifest).toInstant()
                .isAfter(Instant.now().minus(CACHE_TTL));
    }

    @Scheduled(fixedDelay = 3_600_000)
    void removeExpired() {
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        Instant threshold = Instant.now().minus(CACHE_TTL);
        try (var directories = Files.list(cacheRoot)) {
            directories.filter(Files::isDirectory).filter(directory -> {
                try {
                    return Files.getLastModifiedTime(directory).toInstant().isBefore(threshold);
                } catch (IOException error) {
                    return false;
                }
            }).forEach(directory -> {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record CachedPreparation(Mono<Path> task, Instant expiresAt) {
    }
}
