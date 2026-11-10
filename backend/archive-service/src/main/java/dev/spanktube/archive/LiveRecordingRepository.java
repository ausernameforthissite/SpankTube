package dev.spanktube.archive;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class LiveRecordingRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    LiveRecordingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    StartResult start(UUID id, String owner, String videoId, ArchiveModels.ExistingVideo metadata,
            String manifestUrl, Path output) {
        LiveRecording active = active(owner, videoId);
        if (active != null) {
            return new StartResult(active, false);
        }
        try {
            jdbc.update("insert into live_recordings(id, owner_id, video_id, status, metadata, manifest_url, "
                            + "started_at, file_path) values (?, ?, ?, 'RECORDING', ?, ?, ?, ?)",
                    id, owner, videoId, objectMapper.writeValueAsString(metadata), manifestUrl,
                    Timestamp.from(java.time.Instant.now()), output.toString());
            return new StartResult(get(id), true);
        } catch (DuplicateKeyException error) {
            return new StartResult(active(owner, videoId), false);
        }
    }

    LiveRecording latest(String owner, String videoId) {
        return jdbc.query("select * from live_recordings where owner_id = ? and video_id = ? "
                + "order by started_at desc limit 1", this::mapResult, owner, videoId);
    }

    LiveRecording active(String owner, String videoId) {
        return jdbc.query("select * from live_recordings where owner_id = ? and video_id = ? "
                + "and status in ('RECORDING', 'STOPPING') order by started_at desc limit 1",
                this::mapResult, owner, videoId);
    }

    void touchActive(String owner, String videoId) {
        jdbc.update("update live_recordings set last_seen_at = now() "
                + "where owner_id = ? and video_id = ? and status = 'RECORDING'", owner, videoId);
    }

    LiveRecording get(UUID id) {
        return jdbc.query("select * from live_recordings where id = ?", this::mapResult, id);
    }

    LiveRecording stop(String owner, String videoId) {
        LiveRecording active = active(owner, videoId);
        if (active == null) {
            return null;
        }
        markStopping(UUID.fromString(active.id()));
        return get(UUID.fromString(active.id()));
    }

    void markStopping(UUID id) {
        jdbc.update("update live_recordings set status = 'STOPPING', updated_at = now() "
                + "where id = ? and status = 'RECORDING'", id);
    }

    void progress(UUID id, long bytes) {
        jdbc.update("update live_recordings set bytes_downloaded = ?, updated_at = now() where id = ?",
                Math.max(0, bytes), id);
    }

    void complete(UUID id, ArchiveModels.DownloadedFile file, String fileName, String error) {
        jdbc.update("update live_recordings set status = 'COMPLETED', finished_at = now(), updated_at = now(), "
                + "bytes_downloaded = ?, duration_seconds = ?, file_name = ?, error = ? where id = ?",
                file.sizeBytes(), file.durationSeconds(), fileName, error, id);
    }

    void fail(UUID id, String error) {
        jdbc.update("update live_recordings set status = 'FAILED', finished_at = now(), updated_at = now(), "
                + "error = ? where id = ?", error, id);
    }

    Source source(UUID id) {
        return jdbc.query("select metadata, manifest_url from live_recordings where id = ?",
                rs -> rs.next() ? new Source(objectMapper.readValue(rs.getString("metadata"),
                        ArchiveModels.ExistingVideo.class), rs.getString("manifest_url")) : null, id);
    }

    List<ActiveFile> activeFiles() {
        return jdbc.query("select id, owner_id, video_id, file_path, last_seen_at from live_recordings "
                + "where status = 'RECORDING' and file_path is not null",
                (rs, rowNum) -> new ActiveFile(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                        rs.getString("video_id"), Path.of(rs.getString("file_path")),
                        rs.getTimestamp("last_seen_at").toInstant()));
    }

    void recoverInterrupted() {
        jdbc.update("update live_recordings set status = 'FAILED', finished_at = now(), updated_at = now(), "
                + "error = 'archive-service restarted; recording stopped' where status in ('RECORDING', 'STOPPING')");
    }

    private LiveRecording mapResult(ResultSet rs) throws SQLException {
        return rs.next() ? map(rs) : null;
    }

    private LiveRecording map(ResultSet rs) throws SQLException {
        String videoId = rs.getString("video_id");
        String status = rs.getString("status");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        try {
            String title = objectMapper.readTree(rs.getString("metadata")).path("title").asText(videoId);
            return new LiveRecording(rs.getString("id"), videoId, rs.getString("owner_id"), status, title,
                    rs.getTimestamp("started_at").toInstant().toString(),
                    finishedAt == null ? null : finishedAt.toInstant().toString(), rs.getLong("bytes_downloaded"),
                    rs.getObject("duration_seconds", Long.class), rs.getString("file_name"),
                    "COMPLETED".equals(status) ? "/api/v1/library/" + videoId + "/file" : null,
                    rs.getString("error"));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to read live recording metadata", error);
        }
    }

    record StartResult(LiveRecording recording, boolean created) {
    }

    record Source(ArchiveModels.ExistingVideo metadata, String manifestUrl) {
    }

    record ActiveFile(UUID id, String owner, String videoId, Path path, java.time.Instant lastSeenAt) {
    }
}
