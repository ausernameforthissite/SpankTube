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
    void separatesOriginalAndAutoDubbedAudioTracks() {
        UpstreamVideo.Format original = audio("251-ru", "ru", "Русский", true, false);
        UpstreamVideo.Format automatic = audio("251-en-US-dubbed-auto", "en-US", "English (auto)", false, true);
        UpstreamVideo details = new UpstreamVideo("dQw4w9WgXcQ", 2L, List.of(automatic, original), List.of());

        String manifest = new DashManifestGenerator().generate(details);

        assertThat(manifest).contains("id=\"251-ru\"", "id=\"251-en-US-dubbed-auto\"",
                "lang=\"ru\"", "label=\"Русский\"", "<Role value=\"main\"/>", "<Role value=\"dub\"/>");
        assertThat(manifest.indexOf("id=\"251-ru\"")).isLessThan(manifest.indexOf("id=\"251-en-US-dubbed-auto\""));
    }

    private static UpstreamVideo.Format audio(String trackId, String language, String label, boolean original,
            boolean automatic) {
        return new UpstreamVideo.Format("251", "https://rr1.googlevideo.com/audio", "audio/webm", "opus",
                null, null, null, 136_000L, 48_000, null, false, true, "0-258", "259-1989",
                trackId, language, label, original, automatic);
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
