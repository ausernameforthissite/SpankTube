package dev.spanktube.media;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailServiceTest {

    @Test
    void loadsSmallThumbnailDirectlyWithoutMetadataLookup() {
        assertThat(ThumbnailService.thumbnailUrls("3TYZjVzWazE", "hq"))
                .containsExactly("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg",
                        "https://i.ytimg.com/vi/3TYZjVzWazE/mqdefault.jpg",
                        "https://i.ytimg.com/vi/3TYZjVzWazE/default.jpg");
    }

    @Test
    void fallsBackFromMaximumResolution() {
        assertThat(ThumbnailService.thumbnailUrls("3TYZjVzWazE", "max").getFirst())
                .endsWith("/maxresdefault.jpg");
    }

    @Test
    void imageCacheReusesStoredResponse() {
        ImageCache cache = new ImageCache();
        byte[] body = { 1, 2, 3 };
        cache.put("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg", body, MediaType.IMAGE_JPEG);

        assertThat(cache.get("https://i.ytimg.com/vi/3TYZjVzWazE/hqdefault.jpg").body())
                .isSameAs(body);
    }
}
