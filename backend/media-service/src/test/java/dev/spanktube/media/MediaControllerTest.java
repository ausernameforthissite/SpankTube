package dev.spanktube.media;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaControllerTest {

    @Test
    void detectsHlsSegmentContentTypesFromActualContainers() {
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/seg.ts"))
                .toString()).isEqualTo("video/mp2t");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/seg.ts"), "audio")
                .toString()).isEqualTo("audio/aac");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/init.m4s"))
                .toString()).isEqualTo("video/mp4");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/init.mp4"))
                .toString()).isEqualTo("video/mp4");
        assertThat(MediaController.hlsContentType(URI.create("https://rr1.googlevideo.com/file/key.bin"))
                .toString()).isEqualTo("application/octet-stream");
    }
}
