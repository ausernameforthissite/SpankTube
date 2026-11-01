package dev.spanktube.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CatalogRepository {

    private final JdbcTemplate jdbc;

    CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void upsert(ArchiveCompletedEvent event) {
        String previousPath = jdbc.query("select archived_path from videos where video_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, event.videoId());
        if (previousPath != null && !previousPath.equals(event.archivedPath())) {
            jdbc.update("update video_versions set superseded_at = now() where video_id = ? and superseded_at is null",
                    event.videoId());
        }
        jdbc.update("insert into channels(channel_id, title, handle, custom_path, avatar_url, subscriber_count) "
                        + "values (?, ?, ?, ?, ?, ?) on conflict(channel_id) do update set "
                        + "title = excluded.title, handle = excluded.handle, custom_path = excluded.custom_path, "
                        + "avatar_url = excluded.avatar_url, subscriber_count = excluded.subscriber_count",
                event.channelId(), event.channelName(), event.channelHandle(), event.channelCustomPath(),
                event.channelAvatarUrl(), event.channelSubscriberCount());
        jdbc.update("insert into videos(video_id, channel_id, title, description, published_at, duration_sec, archived_path, "
                        + "quality, size_bytes, width, height, video_codec, audio_codec, audio_bitrate) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict(video_id) do update set "
                        + "channel_id = excluded.channel_id, title = excluded.title, description = excluded.description, "
                        + "published_at = excluded.published_at, duration_sec = excluded.duration_sec, "
                        + "archived_path = excluded.archived_path, quality = excluded.quality, size_bytes = excluded.size_bytes, "
                        + "width = excluded.width, height = excluded.height, video_codec = excluded.video_codec, "
                        + "audio_codec = excluded.audio_codec, audio_bitrate = excluded.audio_bitrate",
                event.videoId(), event.channelId(), event.title(), event.description(), event.publishedAt(),
                event.durationSeconds(), event.archivedPath(), event.quality(), event.sizeBytes(), event.width(),
                event.height(), event.videoCodec(), event.audioCodec(), event.audioBitrate());
        jdbc.update("insert into video_versions(id, video_id, channel_id, title, description, published_at, duration_sec, "
                        + "archived_path, quality, size_bytes, width, height, video_codec, audio_codec, audio_bitrate) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict(archived_path) do update set "
                        + "channel_id = excluded.channel_id, title = excluded.title, description = excluded.description, "
                        + "published_at = excluded.published_at, duration_sec = excluded.duration_sec, "
                        + "quality = excluded.quality, size_bytes = excluded.size_bytes, width = excluded.width, "
                        + "height = excluded.height, video_codec = excluded.video_codec, audio_codec = excluded.audio_codec, "
                        + "audio_bitrate = excluded.audio_bitrate, superseded_at = null, deleted_at = null",
                java.util.UUID.randomUUID(), event.videoId(), event.channelId(), event.title(), event.description(),
                event.publishedAt(), event.durationSeconds(), event.archivedPath(), event.quality(), event.sizeBytes(),
                event.width(), event.height(), event.videoCodec(), event.audioCodec(), event.audioBitrate());
        addView(event.ownerUsername(), event.videoId(), 0, 0);
    }

    void addView(String username, String videoId, long positionSeconds, double watchPercent) {
        jdbc.update("insert into user_views(user_id, video_id, last_position_sec, watch_percent) values (?, ?, ?, ?) "
                        + "on conflict(user_id, video_id) do update set last_accessed_at = now(), "
                        + "last_position_sec = excluded.last_position_sec, watch_percent = excluded.watch_percent",
                username, videoId, positionSeconds, watchPercent);
    }

    void updateProgress(String username, String videoId, long positionSeconds, double watchPercent) {
        jdbc.update("insert into user_views(user_id, video_id, last_position_sec, watch_percent) values (?, ?, ?, ?) "
                        + "on conflict(user_id, video_id) do update set last_accessed_at = now(), "
                        + "last_position_sec = excluded.last_position_sec, watch_percent = excluded.watch_percent",
                username, videoId, positionSeconds, watchPercent);
    }

    List<LibraryModels.ProgressEntry> progress(String username) {
        return jdbc.query("select video_id, last_position_sec, watch_percent, last_accessed_at from user_views "
                        + "where user_id = ? order by last_accessed_at desc",
                (rs, rowNum) -> new LibraryModels.ProgressEntry(rs.getString("video_id"),
                        rs.getLong("last_position_sec"), rs.getDouble("watch_percent"), rs.getString("last_accessed_at")),
                username);
    }

    boolean hasLink(String username, String videoId) {
        Integer count = jdbc.queryForObject("select count(*) from user_views where user_id = ? and video_id = ?",
                Integer.class, username, videoId);
        return count != null && count > 0;
    }

    boolean videoExists(String videoId) {
        Integer count = jdbc.queryForObject("select count(*) from videos where video_id = ?", Integer.class, videoId);
        return count != null && count > 0;
    }

    ArchiveCompletedEvent existing(String username, String videoId) {
        return jdbc.query("select c.channel_id, c.title as channel_title, c.handle, c.custom_path, c.avatar_url, c.subscriber_count, "
                        + "v.video_id, v.title as video_title, v.published_at, v.duration_sec, v.archived_path, v.quality, "
                        + "v.size_bytes, v.width, v.height, v.video_codec, v.audio_codec, v.description, v.audio_bitrate "
                        + "from videos v join channels c on c.channel_id = v.channel_id where v.video_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new ArchiveCompletedEvent(username, rs.getString("channel_id"), rs.getString("channel_title"),
                            rs.getString("handle"), rs.getString("custom_path"), rs.getString("avatar_url"),
                            rs.getObject("subscriber_count", Long.class), rs.getString("video_id"),
                            rs.getString("video_title"), rs.getString("published_at"), rs.getObject("duration_sec", Long.class),
                            rs.getString("archived_path"), rs.getString("quality"), rs.getLong("size_bytes"),
                            rs.getObject("width", Integer.class), rs.getObject("height", Integer.class),
                            rs.getString("video_codec"), rs.getString("audio_codec"), rs.getString("description"),
                            rs.getObject("audio_bitrate", Long.class));
                }, videoId);
    }

    VersionedVideo currentVersion(String username, String videoId) {
        return jdbc.query(VERSION_SELECT + " where v.video_id = ? and v.superseded_at is null and v.deleted_at is null "
                        + "order by v.created_at desc limit 1",
                rs -> rs.next() ? mapVersion(rs, username) : null, videoId);
    }

    VersionedVideo version(String username, String videoId, String versionId) {
        return jdbc.query(VERSION_SELECT + " where v.video_id = ? and v.id = ? and v.deleted_at is null",
                rs -> rs.next() ? mapVersion(rs, username) : null, videoId, java.util.UUID.fromString(versionId));
    }

    List<VersionedVideo> versions(String username, String videoId) {
        return jdbc.query(VERSION_SELECT + " where v.video_id = ? and v.deleted_at is null order by v.created_at desc",
                (rs, rowNum) -> mapVersion(rs, username), videoId);
    }

    private static final String VERSION_SELECT = "select v.id, v.video_id, v.title as video_title, v.description, "
            + "v.published_at, v.duration_sec, v.archived_path, v.quality, v.size_bytes, v.width, v.height, "
            + "v.video_codec, v.audio_codec, v.audio_bitrate, v.created_at, v.superseded_at, c.channel_id, c.title as channel_title, "
            + "c.handle, c.custom_path, c.avatar_url, c.subscriber_count from video_versions v "
            + "join channels c on c.channel_id = v.channel_id";

    private static VersionedVideo mapVersion(ResultSet rs, String username) throws SQLException {
        ArchiveCompletedEvent video = new ArchiveCompletedEvent(username, rs.getString("channel_id"),
                rs.getString("channel_title"), rs.getString("handle"), rs.getString("custom_path"),
                rs.getString("avatar_url"), rs.getObject("subscriber_count", Long.class), rs.getString("video_id"),
                rs.getString("video_title"), rs.getString("published_at"), rs.getObject("duration_sec", Long.class),
                rs.getString("archived_path"), rs.getString("quality"), rs.getLong("size_bytes"),
                rs.getObject("width", Integer.class), rs.getObject("height", Integer.class),
                rs.getString("video_codec"), rs.getString("audio_codec"), rs.getString("description"),
                rs.getObject("audio_bitrate", Long.class));
        return new VersionedVideo(rs.getString("id"), video, rs.getString("created_at"), rs.getString("superseded_at"));
    }

    record VersionedVideo(String versionId, ArchiveCompletedEvent video, String createdAt, String supersededAt) {
    }

    long usage(String username) {
        Long used = jdbc.queryForObject("select coalesce(sum(v.size_bytes), 0) from user_views uv "
                + "join video_versions v on v.video_id = uv.video_id and v.deleted_at is null where uv.user_id = ?",
                Long.class, username);
        return used == null ? 0 : used;
    }

    List<LibraryModels.Video> library(String username) {
        return jdbc.query("select v.video_id, v.title, v.published_at, v.duration_sec, v.archived_path, v.quality, v.size_bytes, uv.last_accessed_at, v.width, v.height, v.video_codec, v.audio_codec, v.audio_bitrate "
                        + "from user_views uv join videos v on v.video_id = uv.video_id where uv.user_id = ?",
                this::mapVideo, username);
    }

    List<LibraryModels.Channel> channels(String username) {
        return jdbc.query("select distinct c.channel_id, c.title, c.handle, c.custom_path, c.avatar_url, c.subscriber_count "
                        + "from user_views uv join videos v on v.video_id = uv.video_id "
                        + "join channels c on c.channel_id = v.channel_id where uv.user_id = ?",
                this::mapChannel, username);
    }

    List<LibraryModels.Video> channelVideos(String username, String channelId) {
        return jdbc.query("select v.video_id, v.title, v.published_at, v.duration_sec, v.archived_path, v.quality, v.size_bytes, uv.last_accessed_at, v.width, v.height, v.video_codec, v.audio_codec, v.audio_bitrate "
                        + "from user_views uv join videos v on v.video_id = uv.video_id "
                        + "where uv.user_id = ? and v.channel_id = ?", this::mapVideo, username, channelId);
    }

    String deleteLink(String username, String videoId) {
        String path = jdbc.query("select archived_path from user_views uv join videos v on v.video_id = uv.video_id "
                        + "where uv.user_id = ? and v.video_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, username, videoId);
        if (path == null) {
            return null;
        }
        jdbc.update("delete from user_views where user_id = ? and video_id = ?", username, videoId);
        Integer remaining = jdbc.queryForObject("select count(*) from user_views where video_id = ?", Integer.class, videoId);
        if (remaining != null && remaining == 0) {
            jdbc.update("update video_versions set superseded_at = now(), deleted_at = now() "
                    + "where video_id = ? and archived_path = ?",
                    videoId, path);
            jdbc.update("delete from videos where video_id = ?", videoId);
            return path;
        }
        return null;
    }

    String oldestLink(String username) {
        return jdbc.query("select v.video_id from user_views uv join videos v on v.video_id = uv.video_id "
                        + "where uv.user_id = ? and uv.last_accessed_at < now() - interval '5 minutes' "
                        + "order by uv.last_accessed_at limit 1",
                rs -> rs.next() ? rs.getString(1) : null, username);
    }

    List<String> owners() {
        return jdbc.queryForList("select distinct user_id from user_views order by user_id", String.class);
    }

    private LibraryModels.Video mapVideo(ResultSet rs, int rowNum) throws SQLException {
        return new LibraryModels.Video(rs.getString("video_id"), rs.getString("title"), rs.getString("published_at"),
                rs.getObject("duration_sec", Long.class), rs.getString("archived_path"),
                rs.getString("quality"), rs.getLong("size_bytes"), rs.getString("last_accessed_at"),
                rs.getObject("width", Integer.class), rs.getObject("height", Integer.class),
                rs.getString("video_codec"), rs.getString("audio_codec"), rs.getObject("audio_bitrate", Long.class));
    }

    private LibraryModels.Channel mapChannel(ResultSet rs, int rowNum) throws SQLException {
        String channelId = rs.getString("channel_id");
        String customPath = rs.getString("custom_path");
        String handle = rs.getString("handle");
        String url = customPath != null ? "/c/" + customPath
                : handle != null ? "/@" + handle : "/channel/" + channelId;
        return new LibraryModels.Channel(channelId, rs.getString("title"), handle, customPath, url,
                "/media/channel/" + channelId + "/avatar", rs.getObject("subscriber_count", Long.class));
    }
}
