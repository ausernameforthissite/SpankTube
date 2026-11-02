package dev.spanktube.catalog;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    Map<String, Object> video(@PathVariable String videoId, @RequestParam(required = false) String logSession) {
        Map<String, Object> response = upstreamClient.video(videoId, logSession);
        response.put("sponsorBlock", sponsorBlockClient.segments(videoId));
        Long dislikes = rydClient.dislikes(videoId);
        if (dislikes != null) {
            response.put("dislikeCountEstimate", dislikes);
        }
        return response;
    }
}
