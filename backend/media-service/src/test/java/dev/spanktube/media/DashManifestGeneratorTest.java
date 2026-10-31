package dev.spanktube.media;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashManifestGeneratorTest {

    @Test
    void usesSegmentListWhenUpstreamHasNoIndexRange() {
        UpstreamVideo.Format video = new UpstreamVideo.Format("137", "https://rr1.googlevideo.com/video",
                "video/mp4", "avc1.640028", 1920, 1080, 30.0, 5_000_000L, null,
                null, true, false, "0-740", "741-900");
        UpstreamVideo.Format audio = new UpstreamVideo.Format("140", "https://rr1.googlevideo.com/audio",
                "audio/mp4", "mp4a.40.2", null, null, null, 128_000L, 44_100,
                null, false, true, null, null);
        UpstreamVideo details = new UpstreamVideo("dQw4w9WgXcQ", 2L, List.of(video, audio), List.of());

        String manifest = new DashManifestGenerator().generate(details);

        assertThat(manifest).contains("<SegmentBase indexRange=\"741-900\"");
        assertThat(manifest).contains("<SegmentList duration=\"2\" timescale=\"1\">");
        assertThat(manifest).contains("media=\"/media/v/dQw4w9WgXcQ/seg/140\"");
        assertThat(manifest).doesNotContain("https://rr1.googlevideo.com");
    }

    @Test
    void keepsSingleSegmentWhenIndexIsUnknown() {
        UpstreamVideo.Format audio = new UpstreamVideo.Format("140", "https://rr1.googlevideo.com/audio",
                "audio/mp4", "mp4a.40.2", null, null, null, 128_000L, 44_100,
                5_000_000L, false, true, null, null);
        UpstreamVideo details = new UpstreamVideo("dQw4w9WgXcQ", 2L, List.of(audio), List.of());

        String manifest = new DashManifestGenerator().generate(details);

        assertThat(manifest).contains("timescale=\"1\"");
        assertThat(manifest).doesNotContain("mediaRange=");
        assertThat(manifest).doesNotContain("https://rr1.googlevideo.com");
    }
}
