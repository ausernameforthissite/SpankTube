package dev.spanktube.upstream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class VideoController {

    private final VideoService videoService;

    VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping("/api/v1/videos/{videoId}")
    Mono<PublicVideo> video(@PathVariable String videoId) {
        return videoService.video(videoId).map(PublicVideo::from);
    }

    @GetMapping("/internal/v1/videos/{videoId}")
    Mono<VideoDetails> internalVideo(@PathVariable String videoId) {
        return videoService.video(videoId);
    }
}
