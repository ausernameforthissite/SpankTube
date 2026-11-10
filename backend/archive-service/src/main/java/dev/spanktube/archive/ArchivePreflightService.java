package dev.spanktube.archive;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
class ArchivePreflightService {

    private static final Duration ESTIMATE_TTL = Duration.ofMinutes(15);

    private final UpstreamClient upstreamClient;
    private final CatalogClient catalogClient;
    private final ArchiveSpeedService speedService;
    private final ArchivePreflightEvents events;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, Estimate> estimates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Progress> progress = new ConcurrentHashMap<>();

    ArchivePreflightService(UpstreamClient upstreamClient, CatalogClient catalogClient,
            ArchiveSpeedService speedService, ArchivePreflightEvents events) {
        this.upstreamClient = upstreamClient;
        this.catalogClient = catalogClient;
        this.speedService = speedService;
        this.events = events;
    }

    Mono<Estimate> estimate(ArchiveModels.Kind kind, String targetId, String username) {
        return estimate(kind, targetId, username, null);
    }

    Mono<Estimate> estimate(ArchiveModels.Kind kind, String targetId, String username, String estimateId) {
        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive target is required");
        }
        String id = estimateId == null || estimateId.isBlank() ? UUID.randomUUID().toString() : estimateId;
        Estimate existing = estimates.get(id);
        if (existing != null) {
            if (existing.kind() == kind && existing.targetId().equals(targetId) && existing.owner().equals(username)) {
                return Mono.just(existing);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Archive estimate already exists");
        }
        Progress current = new Progress(id, kind, targetId, username, speedService);
        if (progress.putIfAbsent(id, current) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Archive estimate already exists");
        }
        return Mono.fromCallable(() -> compute(current)).subscribeOn(Schedulers.boundedElastic())
                .filter(estimate -> !current.isCancelled())
                .doOnNext(estimate -> {
                    estimates.put(estimate.estimateId(), estimate);
                    current.complete(estimate);
                })
                .doOnError(error -> {
                    if (current.isCancelled() || error instanceof EstimateCancelledException) {
                        current.cancel();
                    } else {
                        current.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    }
                })
                .onErrorResume(error -> current.isCancelled() || error instanceof EstimateCancelledException
                        ? Mono.empty() : Mono.error(error));
    }

    ProgressSnapshot status(String username, String estimateId) {
        return owned(username, estimateId).snapshot();
    }

    SseEmitter events(String username, String estimateId) {
        return events.register(owned(username, estimateId));
    }

    Runnable subscribe(String username, String estimateId, Consumer<ProgressEvent> listener) {
        return owned(username, estimateId).subscribe(listener);
    }

    void cancel(String username, String estimateId) {
        owned(username, estimateId).cancel();
    }

    Estimate validate(String estimateId, ArchiveModels.Kind kind, String targetId, String username) {
        estimates.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(ESTIMATE_TTL).isBefore(Instant.now()));
        progress.entrySet().removeIf(entry -> entry.getValue().updatedAt.plus(ESTIMATE_TTL).isBefore(Instant.now()));
        Estimate estimate = estimateId == null ? null : estimates.get(estimateId);
        Progress current = progress.get(estimateId);
        if (estimate == null || estimate.kind() != kind || !estimate.targetId().equals(targetId)
                || !estimate.owner().equals(username) || (current != null && current.isCancelled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive estimate is required or expired");
        }
        return estimate;
    }

    private Progress owned(String username, String estimateId) {
        progress.entrySet().removeIf(entry -> entry.getValue().updatedAt.plus(ESTIMATE_TTL).isBefore(Instant.now()));
        Progress current = progress.get(estimateId);
        if (current == null || !current.owner.equals(username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive estimate not found");
        }
        return current;
    }

    private Estimate compute(Progress current) {
        current.checkCancelled();
        List<VideoPage> pages = allVideoPages(current.kind, current.targetId, null, current);
        List<String> videoIds = pages.stream().flatMap(page -> page.videoIds().stream()).toList();
        if (videoIds.isEmpty()) {
            throw new IllegalArgumentException("No videos to archive");
        }
        current.total(pages);
        List<CompletableFuture<VideoEstimate>> tasks = videoIds.stream()
                .map(videoId -> CompletableFuture.supplyAsync(() -> estimateVideo(videoId, current.owner, current), executor)
                        .thenApply(estimate -> {
                            current.add(videoId, estimate);
                            return estimate;
                        }))
                .toList();
        List<VideoEstimate> videos;
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            videos = tasks.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException error) {
            current.checkCancelled();
            throw new ArchiveDownloadException("Unable to estimate archive size: " + error.getMessage(), error);
        }
        current.checkCancelled();
        long videoBytes = videos.stream().filter(video -> !video.existing()).mapToLong(VideoEstimate::videoBytes).sum();
        long audioBytes = videos.stream().filter(video -> !video.existing()).mapToLong(VideoEstimate::audioBytes).sum();
        int existing = (int) videos.stream().filter(VideoEstimate::existing).count();
        return new Estimate(current.id, current.kind, current.targetId, current.owner, videoIds.size(),
                videoIds.size() - existing, existing, videoBytes, audioBytes, videoBytes + audioBytes,
                speedService.estimateSeconds(videoBytes, audioBytes), speedService.videoBps(), speedService.audioBps(),
                videoIds, Instant.now());
    }

    private VideoEstimate estimateVideo(String videoId, String username, Progress current) {
        return current.call(() -> {
            UpstreamClient.VideoDownload download = upstreamClient.video(videoId);
            UpstreamClient.Selection selection = download.select(ArchiveModels.DownloadOptions.archiveDefaults());
            long duration = download.metadata().durationSeconds() == null ? 0 : download.metadata().durationSeconds();
            return new VideoEstimate(UpstreamClient.size(selection.video(), duration),
                    UpstreamClient.size(selection.audio(), duration),
                    catalogClient.existing(username, videoId).isPresent());
        });
    }

    List<String> allVideoIds(ArchiveModels.Kind kind, String targetId, java.util.Map<String, String> publishedAts) {
        return allVideoPages(kind, targetId, publishedAts, null).stream()
                .flatMap(page -> page.videoIds().stream()).toList();
    }

    private List<VideoPage> allVideoPages(ArchiveModels.Kind kind, String targetId,
            java.util.Map<String, String> publishedAts, Progress current) {
        if (kind == ArchiveModels.Kind.PLAYLIST) {
            return collectPages(cursor -> upstreamClient.playlistVideos(targetId, cursor), publishedAts, current, true)
                    .pages();
        }
        if (kind != ArchiveModels.Kind.CHANNEL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Archive estimate is available for playlists and channels");
        }
        if (current != null) {
            current.checkCancelled();
        }
        var videos = CompletableFuture.supplyAsync(
                () -> collectPages(cursor -> upstreamClient.channelVideos(targetId, cursor), publishedAts, current, true),
                executor);
        var streams = CompletableFuture.supplyAsync(
                () -> collectPages(cursor -> upstreamClient.channelLiveStreams(targetId, cursor), publishedAts, current, true),
                executor);
        var playlists = CompletableFuture.supplyAsync(
                () -> collectPages(cursor -> upstreamClient.channelPlaylists(targetId, cursor), publishedAts, current, false),
                executor);
        List<VideoPage> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        merge(result, videos.join().pages(), ids);
        merge(result, streams.join().pages(), ids);
        for (String playlistId : playlists.join().ids()) {
            if (current != null) {
                current.checkCancelled();
            }
            merge(result, collectPages(cursor -> upstreamClient.playlistVideos(playlistId, cursor), publishedAts,
                    current, true).pages(), ids);
        }
        return List.copyOf(result);
    }

    List<String> allPages(Function<String, UpstreamClient.PageItems> page) {
        return collectPages(page, null, null, false).ids();
    }

    private CollectedPages collectPages(Function<String, UpstreamClient.PageItems> page,
            java.util.Map<String, String> publishedAts, Progress current, boolean videoPage) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> seenCursors = new LinkedHashSet<>();
        List<VideoPage> pages = new ArrayList<>();
        String cursor = null;
        for (int sourcePage = 0; sourcePage < 100; sourcePage++) {
            if (current != null) {
                current.checkCancelled();
            }
            int pageNumber = current == null ? sourcePage + 1 : current.nextPageNumber();
            String requestedCursor = cursor;
            UpstreamClient.PageItems result = current == null ? page.apply(requestedCursor)
                    : current.call(() -> page.apply(requestedCursor));
            if (publishedAts != null) {
                publishedAts.putAll(result.publishedAts());
            }
            List<String> pageIds = result.ids().stream()
                    .filter(videoId -> !result.isLive(videoId))
                    .filter(ids::add)
                    .toList();
            if (videoPage) {
                pages.add(new VideoPage(pageNumber, pageIds));
            }
            if (current != null) {
                current.pageOpened(pageNumber, videoPage ? pageIds.size() : 0);
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return new CollectedPages(List.copyOf(ids), List.copyOf(pages));
            }
            if (!seenCursors.add(cursor)) {
                throw new ArchiveDownloadException("Archive pagination returned a repeated cursor");
            }
        }
        throw new ArchiveDownloadException("Archive pagination exceeded 100 pages");
    }

    private static void merge(List<VideoPage> target, List<VideoPage> source, Set<String> ids) {
        for (VideoPage page : source) {
            List<String> unique = page.videoIds().stream().filter(ids::add).toList();
            target.add(new VideoPage(page.pageNumber(), unique));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    record Estimate(String estimateId, ArchiveModels.Kind kind, String targetId, String owner,
            int totalVideoCount, int newVideoCount, int existingVideoCount, long videoBytes, long audioBytes,
            long totalBytes, long estimatedSeconds, long videoBps, long audioBps, List<String> videoIds,
            Instant createdAt) {
        Estimate {
            videoIds = List.copyOf(videoIds);
        }
    }

    record ProgressSnapshot(String estimateId, ArchiveModels.Kind kind, String targetId, String owner, String status,
            int totalVideoCount, int processedVideoCount, int newVideoCount, int existingVideoCount,
            long videoBytes, long audioBytes, long totalBytes, long estimatedSeconds, long videoBps, long audioBps,
            int discoveredVideoCount, int pageNumber, int pageVideoCount, int remainingOnPage,
            double videosPerSecond, String error, Instant createdAt, Instant updatedAt) {
    }

    record ProgressEvent(String name, ProgressSnapshot progress) {
    }

    static final class Progress {
        private final String id;
        private final ArchiveModels.Kind kind;
        private final String targetId;
        private final String owner;
        private final ArchiveSpeedService speedService;
        private final Instant createdAt = Instant.now();
        private final AtomicInteger processedVideoCount = new AtomicInteger();
        private final AtomicInteger newVideoCount = new AtomicInteger();
        private final AtomicInteger existingVideoCount = new AtomicInteger();
        private final AtomicInteger discoveredVideoCount = new AtomicInteger();
        private final AtomicInteger pagesOpened = new AtomicInteger();
        private final AtomicLong videoBytes = new AtomicLong();
        private final AtomicLong audioBytes = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
        private final CopyOnWriteArrayList<Consumer<ProgressEvent>> listeners = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, Integer> videoPages = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, Integer> pageSizes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, AtomicInteger> pageRemaining = new ConcurrentHashMap<>();
        private volatile Instant updatedAt = createdAt;
        private volatile String status = "COLLECTING";
        private volatile int totalVideoCount;
        private volatile int pageNumber;
        private volatile int pageVideoCount;
        private volatile int remainingOnPage;
        private volatile long estimatedSeconds;
        private volatile long videoBps;
        private volatile long audioBps;
        private volatile double videosPerSecond;
        private volatile long analysisStartedNanos;
        private volatile long analysisFinishedNanos;
        private volatile String error;

        private Progress(String id, ArchiveModels.Kind kind, String targetId, String owner,
                ArchiveSpeedService speedService) {
            this.id = id;
            this.kind = kind;
            this.targetId = targetId;
            this.owner = owner;
            this.speedService = speedService;
            videoBps = speedService.videoBps();
            audioBps = speedService.audioBps();
        }

        private int nextPageNumber() {
            checkCancelled();
            return pagesOpened.incrementAndGet();
        }

        private void pageOpened(int page, int videos) {
            if (isCancelled()) {
                return;
            }
            pageNumber = page;
            pageVideoCount = videos;
            remainingOnPage = videos;
            discoveredVideoCount.addAndGet(videos);
            updatedAt = Instant.now();
            emit("page");
        }

        private void total(List<VideoPage> pages) {
            checkCancelled();
            videoPages.clear();
            pageSizes.clear();
            pageRemaining.clear();
            int total = 0;
            for (VideoPage page : pages) {
                int size = page.videoIds().size();
                total += size;
                pageSizes.put(page.pageNumber(), size);
                pageRemaining.put(page.pageNumber(), new AtomicInteger(size));
                for (String videoId : page.videoIds()) {
                    videoPages.put(videoId, page.pageNumber());
                }
            }
            totalVideoCount = total;
            VideoPage first = pages.get(0);
            pageNumber = first.pageNumber();
            pageVideoCount = pageSizes.get(pageNumber);
            remainingOnPage = pageRemaining.get(pageNumber).get();
            analysisStartedNanos = System.nanoTime();
            analysisFinishedNanos = 0;
            updatedAt = Instant.now();
            status = "ESTIMATING";
            emit("progress");
        }

        private void add(String videoId, VideoEstimate estimate) {
            if (isCancelled()) {
                return;
            }
            int page = videoPages.get(videoId);
            int remaining = pageRemaining.get(page).updateAndGet(value -> Math.max(0, value - 1));
            pageNumber = page;
            pageVideoCount = pageSizes.get(page);
            remainingOnPage = remaining;
            int processed = processedVideoCount.incrementAndGet();
            if (estimate.existing()) {
                existingVideoCount.incrementAndGet();
            } else {
                newVideoCount.incrementAndGet();
                videoBytes.addAndGet(estimate.videoBytes());
                audioBytes.addAndGet(estimate.audioBytes());
            }
            estimatedSeconds = speedService.estimateSeconds(videoBytes.get(), audioBytes.get());
            videoBps = speedService.videoBps();
            audioBps = speedService.audioBps();
            videosPerSecond = analysisRate(processed, System.nanoTime());
            updatedAt = Instant.now();
            emit("video");
        }

        private void complete(Estimate estimate) {
            if (isCancelled()) {
                return;
            }
            totalVideoCount = estimate.totalVideoCount();
            processedVideoCount.set(totalVideoCount);
            newVideoCount.set(estimate.newVideoCount());
            existingVideoCount.set(estimate.existingVideoCount());
            videoBytes.set(estimate.videoBytes());
            audioBytes.set(estimate.audioBytes());
            estimatedSeconds = estimate.estimatedSeconds();
            videoBps = estimate.videoBps();
            audioBps = estimate.audioBps();
            remainingOnPage = 0;
            analysisFinishedNanos = System.nanoTime();
            videosPerSecond = analysisRate(totalVideoCount, analysisFinishedNanos);
            updatedAt = Instant.now();
            status = "COMPLETED";
            emit("completed");
        }

        private void fail(String message) {
            if (isCancelled()) {
                return;
            }
            error = message;
            analysisFinishedNanos = System.nanoTime();
            updatedAt = Instant.now();
            status = "FAILED";
            emit("failed");
        }

        void cancel() {
            if (status.equals("COMPLETED") || status.equals("FAILED") || !cancelled.compareAndSet(false, true)) {
                return;
            }
            analysisFinishedNanos = System.nanoTime();
            videosPerSecond = analysisRate(processedVideoCount.get(), analysisFinishedNanos);
            updatedAt = Instant.now();
            status = "CANCELLED";
            workers.forEach(Thread::interrupt);
            emit("cancelled");
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void checkCancelled() {
            if (isCancelled()) {
                throw new EstimateCancelledException();
            }
        }

        private <T> T call(Supplier<T> supplier) {
            Thread thread = Thread.currentThread();
            workers.add(thread);
            try {
                checkCancelled();
                T result = supplier.get();
                checkCancelled();
                return result;
            } finally {
                workers.remove(thread);
                if (isCancelled()) {
                    Thread.interrupted();
                }
            }
        }

        private double analysisRate(int processed, long now) {
            if (processed == 0 || analysisStartedNanos == 0) {
                return 0;
            }
            long end = analysisFinishedNanos == 0 ? now : analysisFinishedNanos;
            return processed * 1_000_000_000.0 / Math.max(1, end - analysisStartedNanos);
        }

        Runnable subscribe(Consumer<ProgressEvent> listener) {
            synchronized (this) {
                listener.accept(new ProgressEvent("progress", snapshot()));
                listeners.add(listener);
            }
            return () -> listeners.remove(listener);
        }

        private void emit(String name) {
            ProgressEvent event;
            synchronized (this) {
                event = new ProgressEvent(name, snapshot());
            }
            listeners.forEach(listener -> listener.accept(event));
        }

        synchronized ProgressSnapshot snapshot() {
            long video = videoBytes.get();
            long audio = audioBytes.get();
            return new ProgressSnapshot(id, kind, targetId, owner, status, totalVideoCount,
                    processedVideoCount.get(), newVideoCount.get(), existingVideoCount.get(), video, audio,
                    video + audio, estimatedSeconds, videoBps, audioBps, discoveredVideoCount.get(), pageNumber,
                    pageVideoCount, remainingOnPage, videosPerSecond, error, createdAt, updatedAt);
        }
    }

    private record VideoPage(int pageNumber, List<String> videoIds) {
        private VideoPage {
            videoIds = List.copyOf(videoIds);
        }
    }

    private record CollectedPages(List<String> ids, List<VideoPage> pages) {
    }

    private record VideoEstimate(long videoBytes, long audioBytes, boolean existing) {
    }

    private static final class EstimateCancelledException extends RuntimeException {
    }
}
