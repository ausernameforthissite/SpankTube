package dev.spanktube.archive;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LiveRecordingManifestTest {

    @TempDir
    Path directory;

    @Test
    void selectsBestHlsVariantAndDefaultAudio() throws Exception {
        Path manifest = directory.resolve("master.m3u8");
        Files.writeString(manifest, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="233",URI="/media/v/live/live/hls/playlist?url=audio-low",DEFAULT=NO
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",URI="/media/v/live/live/hls/playlist?url=audio-main",DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240,AUDIO="233"
                /media/v/live/live/hls/playlist?url=video-low
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="234"
                /media/v/live/live/hls/playlist?url=video-best
                """);

        LiveRecordingManifest.Selection selection = LiveRecordingManifest.select(manifest, "http://media");

        assertThat(selection.inputs()).containsExactly(
                "http://media/media/v/live/live/hls/playlist?url=video-best",
                "http://media/media/v/live/live/hls/playlist?url=audio-main");
        assertThat(selection.maps()).containsExactly("0:v:0", "1:a:0");
    }

    @Test
    void keepsOnlyBestDashVideoAndAudio() throws Exception {
        Path manifest = directory.resolve("source.mpd");
        Files.writeString(manifest, """
                <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
                  <Period>
                    <AdaptationSet contentType="video">
                      <Representation id="240" height="240" bandwidth="500000" />
                      <Representation id="1080" height="1080" bandwidth="5000000" />
                      <SegmentTemplate media="/media/v/live/live/segment?url=video-$Number$" initialization="/media/v/live/live/segment?url=init" />
                    </AdaptationSet>
                    <AdaptationSet contentType="audio">
                      <Representation id="audio" bandwidth="130000" />
                    </AdaptationSet>
                  </Period>
                </MPD>
                """);

        LiveRecordingManifest.Selection selection = LiveRecordingManifest.select(manifest, "http://media");

        String filtered = Files.readString(directory.resolve("recording.mpd"));
        assertThat(filtered).contains("id=\"1080\"", "id=\"audio\"", "http://media/media/v/live/live/segment")
                .doesNotContain("id=\"240\"");
        assertThat(selection.inputs()).containsExactly(directory.resolve("recording.mpd").toString());
        assertThat(selection.maps()).containsExactly("0:v:0", "0:a:0");
    }

    @Test
    void selectsRequestedDashQuality() throws Exception {
        Path manifest = directory.resolve("source.mpd");
        Files.writeString(manifest, """
                <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
                  <Period>
                    <AdaptationSet contentType="video">
                      <Representation id="240" height="240" bandwidth="500000" />
                      <Representation id="1080" height="1080" bandwidth="5000000" />
                    </AdaptationSet>
                    <AdaptationSet contentType="audio">
                      <Representation id="audio" bandwidth="130000" />
                    </AdaptationSet>
                  </Period>
                </MPD>
                """);

        LiveRecordingManifest.select(manifest, "http://media", 240, null);

        assertThat(Files.readString(directory.resolve("recording.mpd")))
                .contains("id=\"240\"", "id=\"audio\"").doesNotContain("id=\"1080\"");
    }

    @Test
    void selectsRequestedHlsQualityAndRejectsUnavailableQuality() throws Exception {
        Path manifest = directory.resolve("master.m3u8");
        Files.writeString(manifest, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="/media/v/live/live/hls/playlist?url=audio",DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240,AUDIO="audio"
                /media/v/live/live/hls/playlist?url=video-low
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
                /media/v/live/live/hls/playlist?url=video-best
                """);

        LiveRecordingManifest.Selection selection = LiveRecordingManifest.select(manifest, "http://media", 240, 500000L);

        assertThat(selection.inputs()).containsExactly(
                "http://media/media/v/live/live/hls/playlist?url=video-low",
                "http://media/media/v/live/live/hls/playlist?url=audio");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                LiveRecordingManifest.select(manifest, "http://media", 720, null))
                .isInstanceOf(ArchiveDownloadException.class)
                .hasMessage("Selected video quality is no longer available");
    }
}
