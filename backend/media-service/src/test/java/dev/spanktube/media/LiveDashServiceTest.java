package dev.spanktube.media;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class LiveDashServiceTest {

    private static final String XML = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic"
                 availabilityStartTime="2026-11-09T12:00:00Z" minimumUpdatePeriod="PT2S">
              <BaseURL>https://manifest.googlevideo.com/dash/base/</BaseURL>
              <Period><AdaptationSet contentType="video"><Representation id="137" bandwidth="1000">
                <BaseURL>../media/</BaseURL>
                <SegmentTemplate timescale="1000" startNumber="100"
                    media="$RepresentationID$/segment-$Number%05d$.m4s" initialization="init-$RepresentationID$.m4s">
                  <SegmentTimeline><S t="0" d="2000" r="3"/></SegmentTimeline>
                </SegmentTemplate>
                <SegmentList><SegmentURL media="segment-100.m4s"/></SegmentList>
              </Representation></AdaptationSet></Period>
            </MPD>
            """;

    @Test
    void rewritesLiveMediaAndPreservesTemplatesAndTimeline() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/dash+xml").body(XML).build()))
                .build();
        UpstreamVideo video = new UpstreamVideo("rFZHOHl-L8A", null, List.of(), List.of(), "LIVE_STREAM",
                "https://manifest.googlevideo.com/api/manifest/dash/id");

        String result = new LiveDashService(client).manifest(video).block();

        assertThat(result).contains("type=\"dynamic\"", "availabilityStartTime=\"2026-11-09T12:00:00Z\"",
                "minimumUpdatePeriod=\"PT2S\"", "<S d=\"2000\" r=\"3\" t=\"0\"");
        assertThat(result).contains("url=https%3A%2F%2Fmanifest.googlevideo.com%2Fdash%2Fmedia%2F"
                + "$RepresentationID$%2Fsegment-$Number%05d$.m4s");
        assertThat(result).contains("url=https%3A%2F%2Fmanifest.googlevideo.com%2Fdash%2Fmedia%2Finit-$RepresentationID$.m4s");
        assertThat(result).contains("url=https%3A%2F%2Fmanifest.googlevideo.com%2Fdash%2Fmedia%2Fsegment-100.m4s");
        assertThat(result).doesNotContain(">https://", "media=\"https://", "initialization=\"https://");
    }

    @Test
    void resolvesBasesWithoutMixingRelativeMediaUrls() {
        String result = LiveDashService.rewrite("video", URI.create("https://manifest.googlevideo.com/api/dash/id"), XML);

        assertThat(result).contains("url=https%3A%2F%2Fmanifest.googlevideo.com%2Fdash%2Fbase%2F");
        assertThat(result).contains("url=https%3A%2F%2Fmanifest.googlevideo.com%2Fdash%2Fmedia%2Fsegment-100.m4s");
    }
}
