package dev.spanktube.archive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class ArchiveController {

    private final ArchiveJobService jobs;
    private final UpstreamClient upstreamClient;
    private final CatalogClient catalogClient;
    private final ArchiveEvents events;
    private final ArchiveProperties properties;
    private final PlaylistThumbnailService playlistThumbnails;
    private final ArchivePreflightService preflights;

    ArchiveController(ArchiveJobService jobs, UpstreamClient upstreamClient, CatalogClient catalogClient,
            ArchiveEvents events, ArchiveProperties properties, PlaylistThumbnailService playlistThumbnails,
            ArchivePreflightService preflights) {
        this.jobs = jobs;
        this.upstreamClient = upstreamClient;
        this.catalogClient = catalogClient;
        this.events = events;
        this.properties = properties;
        this.playlistThumbnails = playlistThumbnails;
        this.preflights = preflights;
    }

    @PostMapping("/api/v1/videos/{videoId}/save")
    ResponseEntity<ArchiveModels.Job> saveVideo(@PathVariable String videoId, HttpServletRequest request) {
        upstreamClient.requireNotLive(videoId);
        return enqueue(ArchiveModels.Kind.VIDEO, videoId, List.of(videoId), request);
    }

    @PostMapping("/api/v1/videos/{videoId}/refresh")
    ResponseEntity<ArchiveModels.Job> refreshVideo(@PathVariable String videoId, HttpServletRequest request) {
        upstreamClient.requireNotLive(videoId);
        return enqueue(ArchiveModels.Kind.REFRESH, videoId, List.of(videoId), request);
    }

    @PostMapping("/api/v1/videos/{videoId}/download")
    ResponseEntity<ArchiveModels.Job> downloadVideo(@PathVariable String videoId,
            @RequestBody DownloadRequest options, HttpServletRequest request) {
        upstreamClient.requireNotLive(videoId);
        ArchiveModels.DownloadOptions downloadOptions = new ArchiveModels.DownloadOptions(
                options.videoFormatId(), options.audioFormatId(),
                options.subtitles() == null || options.subtitles(),
                options.sponsorBlock() == null || options.sponsorBlock());
        ArchiveModels.EnqueueResult result = jobs.enqueue(new ArchiveModels.EnqueueRequest(username(request), role(request),
                ArchiveModels.Kind.DOWNLOAD, videoId, List.of(videoId), ArchiveModels.Purpose.DOWNLOAD, downloadOptions));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.job());
    }

    @PostMapping("/api/v1/playlists/{playlistId}/save")
    ResponseEntity<ArchiveModels.Job> savePlaylist(@PathVariable String playlistId, HttpServletRequest request,
            @RequestParam String estimateId) {
        ArchivePreflightService.Estimate estimate = preflights.validate(estimateId, ArchiveModels.Kind.PLAYLIST,
                playlistId, username(request));
        UpstreamClient.PlaylistArchive playlist = upstreamClient.playlistArchive(playlistId);
        playlistThumbnails.save(playlistId, playlist.thumbnailUrl());
        catalogClient.registerPlaylist(username(request), playlistId, playlist.title(), playlist.channelId(),
                estimate.videoIds());
        return enqueue(ArchiveModels.Kind.PLAYLIST, playlistId, estimate.videoIds(), request);
    }

    @PostMapping("/api/v1/channels/{channelId}/save")
    ResponseEntity<ArchiveModels.Job> saveChannel(@PathVariable String channelId, HttpServletRequest request,
            @RequestParam String estimateId) {
        ArchivePreflightService.Estimate estimate = preflights.validate(estimateId, ArchiveModels.Kind.CHANNEL,
                channelId, username(request));
        registerChannelPlaylists(channelId, username(request));
        return enqueue(ArchiveModels.Kind.CHANNEL, channelId, estimate.videoIds(), request);
    }

    private void registerChannelPlaylists(String channelId, String username) {
        String cursor = null;
        do {
            UpstreamClient.PageItems page = upstreamClient.channelPlaylists(channelId, cursor);
            for (String playlistId : page.ids()) {
                UpstreamClient.PlaylistArchive archive = upstreamClient.playlistArchive(playlistId);
                java.util.LinkedHashSet<String> videoIds = new java.util.LinkedHashSet<>();
                String videoCursor = null;
                do {
                    UpstreamClient.PageItems videos = upstreamClient.playlistVideos(playlistId, videoCursor);
                    videoIds.addAll(videos.ids());
                    videoCursor = videos.nextCursor();
                } while (videoCursor != null);
                catalogClient.registerPlaylist(username, playlistId, archive.title(), archive.channelId(),
                        java.util.List.copyOf(videoIds));
            }
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    @GetMapping("/api/v1/archive/jobs")
    List<ArchiveModels.Job> jobs(HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return jobs.jobs(username(request), admin(request), status);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/archive/jobs")
    ResponseEntity<Void> clearCompleted(HttpServletRequest request) {
        jobs.clearCompleted(username(request), admin(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/archive/jobs/{jobId}")
    ArchiveModels.Job job(@PathVariable String jobId, HttpServletRequest request) {
        ArchiveModels.Job job = jobs.job(jobId);
        if (job == null || (!admin(request) && !job.ownerUsername().equals(username(request)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive job not found");
        }
        return job;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/archive/jobs/{jobId}")
    ResponseEntity<Void> dismiss(@PathVariable String jobId, HttpServletRequest request) {
        ArchiveModels.Job job = job(jobId, request);
        if (!"completed".equals(job.status()) && !"failed".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed jobs can be dismissed");
        }
        jobs.dismiss(jobId, username(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/archive/jobs/{jobId}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable String jobId, HttpServletRequest request) throws Exception {
        ArchiveModels.Job job = job(jobId, request);
        if (job.kind() != ArchiveModels.Kind.DOWNLOAD || job.downloadUrl() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Download not found");
        }
        ArchiveRepository.JobFile file = jobs.file(jobId);
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Download not found");
        }
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path path = file.path().toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Download file not found");
        }
        boolean mp3 = file.filename().endsWith(".mp3");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mp3 ? "audio/mpeg" : "video/x-matroska"))
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @GetMapping("/api/v1/archive/events")
    SseEmitter events(HttpServletRequest request) {
        return events.register(username(request), admin(request));
    }

    private ResponseEntity<ArchiveModels.Job> enqueue(ArchiveModels.Kind kind, String targetId,
            List<String> videoIds, HttpServletRequest request) {
        return enqueue(kind, targetId, videoIds, request,
                new ArchiveModels.EnqueueRequest(username(request), role(request), kind, targetId, videoIds));
    }

    private ResponseEntity<ArchiveModels.Job> enqueue(ArchiveModels.Kind kind, String targetId,
            List<String> videoIds, HttpServletRequest request, ArchiveModels.EnqueueRequest enqueue) {
        ArchiveModels.EnqueueResult result = jobs.enqueue(enqueue);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.job());
    }

    private static String username(HttpServletRequest request) {
        String username = request.getHeader("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        return username;
    }

    private static boolean admin(HttpServletRequest request) {
        String roles = request.getHeader("X-SpankTube-Roles");
        return roles != null && roles.contains("ROLE_ADMIN");
    }

    private static String role(HttpServletRequest request) {
        return admin(request) ? "ADMIN" : "VIEWER";
    }

    record DownloadRequest(String videoFormatId, String audioFormatId,
            Boolean subtitles, Boolean sponsorBlock) {
    }
}
