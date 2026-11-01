package dev.spanktube.upstream;

public interface UpstreamProvider {

    VideoDetails video(String videoId);

    SearchResults search(String query, String type, String cursor);

    default ChannelDetails channel(String channelId) {
        return channel(channelId, null);
    }

    ChannelDetails channel(String channelId, String cursor);

    default SearchResults channelVideos(String channelId, String cursor) {
        ChannelDetails channel = channel(channelId, cursor);
        return new SearchResults(channel.videos(), channel.nextCursor());
    }

    default SearchResults channelPlaylists(String channelId, String cursor) {
        return new SearchResults(channel(channelId).playlists(), null);
    }

    default PlaylistDetails playlist(String playlistId) {
        return playlist(playlistId, null);
    }

    PlaylistDetails playlist(String playlistId, String cursor);
}
