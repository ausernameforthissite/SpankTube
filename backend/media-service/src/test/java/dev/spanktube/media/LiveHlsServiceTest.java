package dev.spanktube.media;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveHlsServiceTest {

    @Test
    void rewritesVariantPlaylistsSegmentsKeysAndInitialization() {
        String master = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio/playlist.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                variants/720p
                """;
        String result = LiveHlsService.rewrite("live123", URI.create(
                "https://manifest.googlevideo.com/api/manifest/hls_playlist/master"), master);

        assertThat(result).contains("#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720");
        assertThat(result).contains("URI=\"/media/v/live123/live/hls/playlist?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fapi%2Fmanifest%2Fhls_playlist%2Faudio%2Fplaylist.m3u8&kind=audio\"");
        assertThat(result).contains("/media/v/live123/live/hls/playlist?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fapi%2Fmanifest%2Fhls_playlist%2Fvariants%2F720p");
        assertThat(result).doesNotContain("URI=\"https://", "\nhttps://");
    }

    @Test
    void rejectsEmptyPlaylistInsteadOfReturningFakeSuccess() {
        org.springframework.web.reactive.function.client.WebClient client = org.springframework.web.reactive.function.client.WebClient
                .builder().exchangeFunction(request -> reactor.core.publisher.Mono.just(
                        org.springframework.web.reactive.function.client.ClientResponse.create(
                                org.springframework.http.HttpStatus.NO_CONTENT).build())).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LiveHlsService(client)
                .manifest("live123", URI.create("https://manifest.googlevideo.com/hls_variant/playlist")).block())
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getReason()).contains("Empty HLS playlist", "204"));
    }

    @Test
    void allowsLargeDvrPlaylists() {
        String playlist = "#EXTM3U\n#EXTINF:4,\n" + "segment.ts\n".repeat(30_000);
        var strategies = org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        org.springframework.web.reactive.function.client.WebClient client = org.springframework.web.reactive.function.client.WebClient
                .builder().exchangeFunction(request -> reactor.core.publisher.Mono.just(
                        org.springframework.web.reactive.function.client.ClientResponse.create(
                                org.springframework.http.HttpStatus.OK, strategies)
                                .header("Content-Type", "application/vnd.apple.mpegurl").body(playlist).build())).build();

        String result = new LiveHlsService(client)
                .manifest("live123", URI.create("https://manifest.googlevideo.com/hls_variant/playlist")).block();

        assertThat(result).hasSizeGreaterThan(256 * 1024);
        assertThat(result).contains("/live/hls/segment.ts?url=");
    }

    @Test
    void keepsLiveTimelineAndProxiesMediaResources() {
        String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXT-X-MEDIA-SEQUENCE:1000
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1234
                #EXTINF:4.0,
                segment-1000.ts
                """;
        String result = LiveHlsService.rewrite("live123", URI.create(
                "https://manifest.googlevideo.com/hls_variant/playlist"), media);

        assertThat(result).contains("#EXT-X-MEDIA-SEQUENCE:1000", "#EXTINF:4.0,", "IV=0x1234");
        assertThat(result).contains("URI=\"/media/v/live123/live/hls/segment.mp4?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fhls_variant%2Finit.mp4\"");
        assertThat(result).contains("URI=\"/media/v/live123/live/hls/segment.bin?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fhls_variant%2Fkey.bin\"");
        assertThat(result).contains("/media/v/live123/live/hls/segment.ts?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fhls_variant%2Fsegment-1000.ts");
    }

    @Test
    void preservesRawAacContainerFromAudioPlaylist() {
        String media = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1000\n#EXTINF:5.0,\nsegment-1000.ts\n";

        String result = LiveHlsService.rewrite("live123", URI.create(
                "https://manifest.googlevideo.com/hls_variant/playlist"), media, "audio");

        assertThat(result).contains("/media/v/live123/live/hls/segment.aac?url="
                + "https%3A%2F%2Fmanifest.googlevideo.com%2Fhls_variant%2Fsegment-1000.ts&kind=audio");
        assertThat(result).doesNotContain("/segment.ts?url=");
    }
}
