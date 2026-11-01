package dev.spanktube.upstream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class YtDlpResponseParserTest {

    private final YtDlpResponseParser parser = new YtDlpResponseParser(new ObjectMapper());

    @Test
    void parsesChannelWithLocalRoutesAndAvatarMetadata() {
        String json = """
                {
                  "id": "UC123",
                  "title": "Test channel",
                  "channel_url": "https://www.youtube.com/@tester",
                  "subscriber_count": 42,
                  "thumbnails": [{"url": "https://i.ytimg.com/avatar.jpg"}],
                  "entries": [
                    {"_type": "url", "ie_key": "Youtube", "id": "abc12345678", "title": "Video"},
                    {"_type": "playlist", "ie_key": "YoutubePlaylist", "id": "PL123", "title": "List"}
                  ]
                }
                """;

        ChannelDetails details = parser.channel(json);

        assertThat(details.avatarUrl()).isEqualTo("https://i.ytimg.com/avatar.jpg");
        assertThat(details.handle()).isEqualTo("tester");
        assertThat(details.videos().getFirst().url()).isEqualTo("/watch?v=abc12345678");
        assertThat(details.videos().getFirst().thumbnailUrl()).isEqualTo("/media/v/abc12345678/thumbnail");
        assertThat(details.playlists().getFirst().url()).isEqualTo("/playlist?list=PL123");
    }

    @Test
    void parsesSearchKindsWithoutUpstreamUrls() {
        String json = """
                {"entries": [
                  {"_type": "url", "ie_key": "Youtube", "id": "abc12345678", "title": "Video"},
                  {"_type": "url", "ie_key": "YoutubeTab", "id": "UC123", "url": "https://www.youtube.com/channel/UC123", "title": "Channel"},
                  {"_type": "url", "ie_key": "YoutubeTab", "id": "PL123", "url": "https://www.youtube.com/playlist?list=PL123", "title": "List"}
                ]}
                """;

        SearchResults results = parser.search(json);

        assertThat(results.items()).extracting(SearchItem::kind)
                .containsExactly("video", "channel", "playlist");
        assertThat(results.items()).extracting(SearchItem::thumbnailUrl)
                .containsExactly("/media/v/abc12345678/thumbnail", "/media/channel/UC123/avatar",
                        "/media/playlist/PL123/thumbnail");
        assertThat(results.items()).allSatisfy(item -> assertThat(item.url()).doesNotContain("youtube.com"));
    }
}
