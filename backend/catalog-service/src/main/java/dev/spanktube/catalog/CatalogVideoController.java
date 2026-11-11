package dev.spanktube.catalog;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
class CatalogVideoController {

    private final UpstreamClient upstreamClient;
    private final SponsorBlockClient sponsorBlockClient;
    private final RydClient rydClient;

    CatalogVideoController(UpstreamClient upstreamClient, SponsorBlockClient sponsorBlockClient, RydClient rydClient) {
        this.upstreamClient = upstreamClient;
        this.sponsorBlockClient = sponsorBlockClient;
        this.rydClient = rydClient;
    }

    @GetMapping("/api/v1/videos/{videoId}")
    Mono<Map<String, Object>> video(@PathVariable String videoId, @RequestParam(required = false) String logSession) {
        Mono<Map<String, Object>> video = Mono.fromCallable(() -> upstreamClient.video(videoId, logSession))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<java.util.List<SponsorBlockClient.Segment>> segments = Mono.fromCallable(() -> sponsorBlockClient.segments(videoId))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Long> dislikes = Mono.fromCallable(() -> rydClient.dislikes(videoId))
                .subscribeOn(Schedulers.boundedElastic());
        return Mono.zip(video, segments, dislikes)
                .map(result -> {
                    Map<String, Object> response = result.getT1();
                    response.put("sponsorBlock", result.getT2());
                    if (result.getT3() != null) {
                        response.put("dislikeCountEstimate", result.getT3());
                    }
                    return response;
                });
    }
}
