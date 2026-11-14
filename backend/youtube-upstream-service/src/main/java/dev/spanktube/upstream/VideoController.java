package dev.spanktube.upstream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class VideoController {

    private final VideoService videoService;
    private final MetadataLogService logs;

    VideoController(VideoService videoService, MetadataLogService logs) {
        this.videoService = videoService;
        this.logs = logs;
    }

    @GetMapping("/api/v1/videos/{videoId}")
    Mono<PublicVideo> video(@PathVariable String videoId, @RequestParam(required = false) String logSession) {
        logs.log(logSession, "Получение метаданных видео…");
        return videoService.video(videoId).map(PublicVideo::from)
                .doOnNext(result -> logs.log(logSession, "Метаданные видео получены"))
                .doOnError(error -> logs.error(logSession, error));
    }

    @GetMapping("/internal/v1/videos/{videoId}")
    Mono<VideoDetails> internalVideo(@PathVariable String videoId) {
        return videoService.video(videoId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/internal/v1/videos/{videoId}/refresh")
    Mono<VideoDetails> refresh(@PathVariable String videoId) {
        return videoService.refresh(videoId);
    }
}
