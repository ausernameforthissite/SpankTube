package dev.spanktube.archive;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class LiveRecordingController {

    private final LiveRecordingService recordings;

    LiveRecordingController(LiveRecordingService recordings) {
        this.recordings = recordings;
    }

    @PostMapping("/api/v1/videos/{videoId}/recording/start")
    ResponseEntity<LiveRecording> start(@PathVariable String videoId, HttpServletRequest request,
            @RequestBody(required = false) LiveRecordingRequest quality) {
        LiveRecordingRepository.StartResult result = recordings.start(username(request), videoId, quality);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.recording());
    }

    @PostMapping("/api/v1/videos/{videoId}/recording/stop")
    ResponseEntity<LiveRecording> stop(@PathVariable String videoId, HttpServletRequest request) {
        LiveRecording recording = recordings.stop(username(request), videoId);
        return ResponseEntity.status("COMPLETED".equals(recording.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(recording);
    }

    @GetMapping("/api/v1/videos/{videoId}/recording")
    ResponseEntity<LiveRecording> status(@PathVariable String videoId, HttpServletRequest request) {
        LiveRecording recording = recordings.status(username(request), videoId);
        if (recording == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No recording for this live stream");
        }
        return ResponseEntity.ok(recording);
    }

    private static String username(HttpServletRequest request) {
        String username = request.getHeader("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        return username;
    }
}
