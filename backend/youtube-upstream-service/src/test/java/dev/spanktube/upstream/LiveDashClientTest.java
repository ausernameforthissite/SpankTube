package dev.spanktube.upstream;

import com.grack.nanojson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveDashClientTest {

    @Test
    void readsOnlyDashFromWebPlayerResponse() {
        var response = response("https://manifest.googlevideo.com/api/manifest/dash/id?foo=bar");

        assertThat(LiveDashClient.fromPlayerResponse(response)).contains(
                "https://manifest.googlevideo.com/api/manifest/dash/id?foo=bar&mpd_version=7");
    }

    @Test
    void readsHlsWhenDashIsAbsent() {
        assertThat(LiveDashClient.fromPlayerResponse(response(null))).isEmpty();
        assertThat(LiveDashClient.hlsFromPlayerResponse(response(null)))
                .contains("https://manifest.googlevideo.com/api/manifest/hls_playlist/id");
    }

    @Test
    void mapsVisionOsLiveMetadata() {
        var details = new JsonObject();
        details.put("title", "Live title");
        details.put("author", "Channel");
        details.put("channelId", "UC123");
        details.put("shortDescription", "Description<br>line");
        details.put("viewCount", "42");
        details.put("isLive", true);
        var response = response(null);
        response.put("videoDetails", details);

        VideoDetails video = LiveDashClient.fromVisionOs("rFZHOHl-L8A", response).orElseThrow();

        assertThat(video.title()).isEqualTo("Live title");
        assertThat(video.channelName()).isEqualTo("Channel");
        assertThat(video.channelId()).isEqualTo("UC123");
        assertThat(video.viewCount()).isEqualTo(42);
        assertThat(video.durationSeconds()).isNull();
        assertThat(video.liveHlsUrl()).isEqualTo("https://manifest.googlevideo.com/api/manifest/hls_playlist/id");
        assertThat(PublicVideo.from(video).manifestUrl()).isEqualTo("/media/v/rFZHOHl-L8A/live.m3u8");
    }

    private static JsonObject response(String dashUrl) {
        var playability = new JsonObject();
        playability.put("status", "OK");
        var streaming = new JsonObject();
        if (dashUrl != null) {
            streaming.put("dashManifestUrl", dashUrl);
        }
        streaming.put("hlsManifestUrl", "https://manifest.googlevideo.com/api/manifest/hls_playlist/id");
        var response = new JsonObject();
        response.put("playabilityStatus", playability);
        response.put("streamingData", streaming);
        return response;
    }
}
