package dev.spanktube.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.spanktube.egress.MediaRedirectCache;
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
    private final MediaRedirectCache redirectCache;
    private final ConcurrentHashMap<String, CachedPreparation> preparations = new ConcurrentHashMap<>();

    ProgressiveDashService(@Qualifier("youtubeWebClient") WebClient youtubeClient, UpstreamClient upstreamClient,
            PlaybackLogService playbackLogs,
            @Value("${spanktube.media.dash-cache-root:/tmp/spanktube-dash}") String cacheRoot,
            @Value("${spanktube.media.ffmpeg-executable:ffmpeg}") String ffmpegExecutable,
            MediaRedirectCache redirectCache) {
        this.youtubeClient = youtubeClient;
        this.upstreamClient = upstreamClient;
        this.playbackLogs = playbackLogs;
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        this.ffmpegExecutable = ffmpegExecutable;
        this.redirectCache = redirectCache;
    }

    static boolean requiresRemux(UpstreamVideo video) {
        return video.formats().size() == 1 && video.formats().getFirst().hasVideo()
                && video.formats().getFirst().hasAudio() && video.formats().getFirst().indexRange() == null;
    }

    Mono<String> manifest(UpstreamVideo video, String playbackSession) {
        return prepare(video, playbackSession).map(path -> {
            try {
                return withPresentationDuration(Files.readString(path.resolve("manifest.mpd")), video.durationSeconds());
            } catch (IOException error) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read DASH manifest", error);
            }
        });
    }

    static String withPresentationDuration(String manifest, Long durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0 || !manifest.contains("type=\"dynamic\"")) {
            return manifest;
        }
        String duration = Duration.ofSeconds(durationSeconds).toString();
        if (manifest.contains("mediaPresentationDuration=\"")) {
            return manifest.replaceFirst("mediaPresentationDuration=\"[^\"]*\"",
                    "mediaPresentationDuration=\"" + duration + "\"");
        }
        return manifest.replaceFirst("<MPD\\s", "<MPD mediaPresentationDuration=\"" + duration + "\" ");
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
        if (cached != null && cached.expiresAt().isAfter(Instant.now())
                && !cached.completion().isCompletedExceptionally()) {
            return cached.task;
        }
        URI source = MediaUrlValidator.mediaUrl(video.formats().getFirst().url());
        java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
        Mono<Path> task = Mono.defer(() -> {
                    Path directory = cacheRoot.resolve(videoId).normalize();
                    Path input = directory.resolve("source.mp4");
                    Path manifest = directory.resolve("manifest.mpd");
                    try {
                        Files.createDirectories(directory);
                        if (isFresh(manifest)) {
                            completion.complete(null);
                            return Mono.just(directory);
                        }
                    } catch (IOException error) {
                        return Mono.error(error);
                    }
                    playbackLogs.log(videoId, playbackSession, "Скачивание MP4 и DASH remux выполняются параллельно…");
                    reactor.core.Disposable downloading = downloadAndRemux(source, input, directory)
                            .subscribe(path -> {
                                completion.complete(null);
                                playbackLogs.log(videoId, playbackSession, "DASH remux завершён");
                            }, error -> {
                                completion.completeExceptionally(error);
                                playbackLogs.error(videoId, playbackSession, error);
                            });
                    return awaitReady(directory, completion)
                            .doOnNext(path -> playbackLogs.log(videoId, playbackSession,
                                    "Первые DASH-фрагменты готовы; скачивание продолжается…"))
                            .doOnError(error -> downloading.dispose())
                            .doOnCancel(downloading::dispose);
                })
                .doOnError(error -> {
                    preparations.remove(videoId);
                    playbackLogs.error(videoId, playbackSession, error);
                })
                .cache();
        CachedPreparation next = new CachedPreparation(task, Instant.now().plus(CACHE_TTL), completion);
        preparations.put(videoId, next);
        completion.whenComplete((unused, error) -> {
            if (error != null) {
                preparations.remove(videoId, next);
            }
        });
        return task;
    }

    private Mono<Path> awaitReady(Path directory, java.util.concurrent.CompletableFuture<Void> completion) {
        return Mono.defer(() -> Mono.fromCallable(() -> readyDirectory(directory, completion))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(50)))
                .timeout(Duration.ofMinutes(10));
    }

    private static final long DOWNLOAD_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("^bytes \\d+-\\d+/(\\d+|\\*)$");

    Mono<Void> download(URI source, Path destination) {
        return download(source, destination, null);
    }

    Mono<Void> download(URI source, Path destination, OutputStream ffmpegInput) {
        return Mono.fromCallable(() -> {
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            long offset = Files.isRegularFile(destination) ? Files.size(destination) : 0L;
            if (ffmpegInput != null && offset > 0) {
                try (InputStream existing = Files.newInputStream(destination)) {
                    existing.transferTo(ffmpegInput);
                }
                ffmpegInput.flush();
            }
            return offset;
        }).flatMap(offset -> downloadChunk(source, destination, offset, null, ffmpegInput));
    }

    private Mono<Void> downloadChunk(URI source, Path destination, long offset, Long total, OutputStream ffmpegInput) {
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
                    if (ffmpegInput != null) {
                        return Mono.fromCallable(() -> {
                            try (InputStream downloaded = Files.newInputStream(destination)) {
                                downloaded.skipNBytes(offset);
                                downloaded.transferTo(ffmpegInput);
                                ffmpegInput.flush();
                            }
                            return chunk;
                        }).flatMap(validated -> validated.hasMore()
                                ? downloadChunk(source, destination, chunk.downloaded(), chunk.total(), ffmpegInput)
                                : Mono.empty());
                    }
                    return chunk.hasMore()
                            ? downloadChunk(source, destination, chunk.downloaded(), chunk.total(), ffmpegInput)
                            : Mono.empty();
                });
    }

    private Mono<ChunkDownload> requestChunk(URI source, Path destination, long offset, long end, Long total) {
        URI target = redirectCache.resolve(source);
        return youtubeClient.get().uri(target)
                .header(org.springframework.http.HttpHeaders.RANGE, "bytes=" + offset + "-" + end)
                .retrieve()
                .toEntityFlux(DataBuffer.class)
                .flatMap(response -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        redirectCache.invalidate(target);
                    }
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

    Mono<Path> downloadAndRemux(URI source, Path input, Path directory) {
        Process process;
        try {
            process = startRemux(directory, null);
        } catch (IOException error) {
            return Mono.error(error);
        }
        OutputStream ffmpegInput = process.getOutputStream();
        return download(source, input, ffmpegInput)
                .then(Mono.fromRunnable(() -> {
                    closeQuietly(ffmpegInput);
                    awaitRemux(process);
                }))
                .thenReturn(directory)
                .doOnError(error -> {
                    closeQuietly(ffmpegInput);
                    process.destroyForcibly();
                })
                .doOnCancel(() -> {
                    closeQuietly(ffmpegInput);
                    process.destroyForcibly();
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    void remux(Path input, Path directory) {
        try {
            awaitRemux(startRemux(directory, input));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to remux progressive MP4", error);
        }
    }

    private Process startRemux(Path directory, Path input) throws IOException {
        Files.createDirectories(directory.resolve("remux"));
        return new ProcessBuilder(ffmpegExecutable, "-hide_banner", "-loglevel", "error", "-y",
                "-i", input == null ? "pipe:0" : input.toString(),
                "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy",
                "-f", "dash", "-seg_duration", "4", "-use_template", "1", "-use_timeline", "1",
                "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                "-init_seg_name", "remux/init-$RepresentationID$.m4s",
                "-media_seg_name", "remux/chunk-$RepresentationID$-$Number%05d$.m4s",
                directory.resolve("manifest.mpd").toString())
                .redirectErrorStream(true).start();
    }

    private void awaitRemux(Process process) {
        try {
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
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    private static Path readyDirectory(Path directory, java.util.concurrent.CompletableFuture<Void> completion)
            throws IOException {
        if (completion.isCompletedExceptionally()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to prepare progressive MP4",
                    completion.exceptionNow());
        }
        try {
            org.w3c.dom.Document manifest = readManifest(directory.resolve("manifest.mpd"));
            String type = manifestType(manifest);
            if (("dynamic".equals(type) || "static".equals(type))
                    && manifest.getElementsByTagName("SegmentTimeline").getLength() > 1
                    && firstSegmentsReady(directory)) {
                return directory;
            }
        } catch (IOException error) {
            if (completion.isDone()) {
                throw error;
            }
        }
        if (completion.isDone()) {
            throw new IOException("DASH remux completed without a playable manifest");
        }
        return null;
    }

    private static boolean firstSegmentsReady(Path directory) throws IOException {
        for (String filename : List.of("init-0.m4s", "init-1.m4s", "chunk-0-00001.m4s", "chunk-1-00001.m4s")) {
            Path file = directory.resolve("remux").resolve(filename);
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return false;
            }
        }
        return true;
    }

    private static org.w3c.dom.Document readManifest(Path manifest) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(manifest.toFile());
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException error) {
            throw new IOException("Invalid DASH manifest", error);
        }
    }

    private static String manifestType(org.w3c.dom.Document manifest) {
        return "MPD".equals(manifest.getDocumentElement().getTagName())
                ? manifest.getDocumentElement().getAttribute("type") : null;
    }

    private static boolean isFresh(Path manifest) throws IOException {
        return Files.isRegularFile(manifest) && Files.getLastModifiedTime(manifest).toInstant()
                .isAfter(Instant.now().minus(CACHE_TTL)) && "static".equals(manifestType(readManifest(manifest)));
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

    private record CachedPreparation(Mono<Path> task, Instant expiresAt,
            java.util.concurrent.CompletableFuture<Void> completion) {
    }
}
