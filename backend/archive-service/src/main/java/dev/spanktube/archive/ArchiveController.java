package dev.spanktube.archive;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ArchiveController {

    private final ArchiveJobService jobs;
    private final UpstreamClient upstreamClient;

    ArchiveController(ArchiveJobService jobs, UpstreamClient upstreamClient) {
        this.jobs = jobs;
        this.upstreamClient = upstreamClient;
    }

    @PostMapping("/api/v1/videos/{videoId}/save")
    ResponseEntity<ArchiveModels.Job> saveVideo(@PathVariable String videoId, HttpServletRequest request) {
        return enqueue(ArchiveModels.Kind.VIDEO, videoId, List.of(videoId), request);
    }

    @PostMapping("/api/v1/playlists/{playlistId}/save")
    ResponseEntity<ArchiveModels.Job> savePlaylist(@PathVariable String playlistId, HttpServletRequest request) {
        return enqueue(ArchiveModels.Kind.PLAYLIST, playlistId, upstreamClient.playlistVideos(playlistId), request);
    }

    @PostMapping("/api/v1/channels/{channelId}/save")
    ResponseEntity<ArchiveModels.Job> saveChannel(@PathVariable String channelId, HttpServletRequest request) {
        return enqueue(ArchiveModels.Kind.CHANNEL, channelId, upstreamClient.channelVideos(channelId), request);
    }

    @GetMapping("/api/v1/archive/jobs")
    List<ArchiveModels.Job> jobs(HttpServletRequest request,
            @RequestParam(required = false) String status) {
        String username = username(request);
        boolean admin = admin(request);
        return jobs.jobs(username, admin, status);
    }

    @GetMapping("/api/v1/archive/jobs/{jobId}")
    ArchiveModels.Job job(@PathVariable String jobId, HttpServletRequest request) {
        ArchiveModels.Job job = jobs.job(jobId);
        if (job == null || (!admin(request) && !job.ownerUsername().equals(username(request)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive job not found");
        }
        return job;
    }

    private ResponseEntity<ArchiveModels.Job> enqueue(ArchiveModels.Kind kind, String targetId,
            List<String> videoIds, HttpServletRequest request) {
        String jobId = jobs.enqueue(new ArchiveModels.EnqueueRequest(username(request), role(request), kind, targetId, videoIds));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.job(jobId));
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
}
