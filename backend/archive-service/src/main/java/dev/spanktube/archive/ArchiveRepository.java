package dev.spanktube.archive;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ArchiveRepository {

    private final JdbcTemplate jdbc;

    ArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String createJob(ArchiveModels.EnqueueRequest request) {
        UUID id = UUID.randomUUID();
        int priority = "ADMIN".equals(request.role()) ? 0 : 100;
        jdbc.update("insert into archive_jobs(id, owner_id, owner_role, kind, target_id, priority, status, total_count, "
                        + "options_key, title) values (?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?)",
                id, request.owner(), request.role(), request.kind().name(), request.targetId(), priority,
                request.videoIds().size(), request.options().signature(), request.targetId());
        request.videoIds().forEach(videoId -> jdbc.update(
                "insert into archive_job_items(job_id, video_id, status) values (?, ?, 'queued') "
                        + "on conflict(job_id, video_id) do nothing", id, videoId));
        return id.toString();
    }

    String activeVideoJob(String videoId, String owner) {
        return jdbc.query("select j.id from archive_job_items i join archive_jobs j on j.id = i.job_id "
                        + "where i.video_id = ? and j.owner_id = ? and j.kind = 'VIDEO' "
                        + "and i.status in ('queued', 'running') and j.status in ('queued', 'running') "
                        + "order by j.created_at desc limit 1",
                rs -> rs.next() ? rs.getString(1) : null, videoId, owner);
    }

    ArchiveModels.Job activeDownloadJob(String videoId, String owner, String signature) {
        return jdbc.query("select * from archive_jobs where kind = 'DOWNLOAD' and target_id = ? and owner_id = ? "
                        + "and options_key = ? and status in ('queued', 'running') order by created_at desc limit 1",
                rs -> rs.next() ? mapJob(rs) : null, videoId, owner, signature);
    }

    ArchiveModels.Job reusableDownload(String videoId, String owner, String signature) {
        return jdbc.query("select * from archive_jobs where kind = 'DOWNLOAD' and target_id = ? and owner_id = ? "
                        + "and options_key = ? and status = 'completed' and download_url is not null "
                        + "and temporary_file = true and finished_at >= now() - interval '24 hours' "
                        + "order by finished_at desc limit 1",
                rs -> rs.next() ? mapJob(rs) : null, videoId, owner, signature);
    }

    ArchiveModels.Job nextQueuedJob() {
        return jdbc.query("select * from archive_jobs j where j.status in ('queued', 'running') "
                        + "and exists (select 1 from archive_job_items i where i.job_id = j.id "
                        + "and i.status = 'queued' and i.next_attempt_at <= now() "
                        + "and not exists (select 1 from archive_job_items other where other.video_id = i.video_id "
                        + "and other.status = 'running' and other.job_id <> j.id)) "
                        + "and not exists (select 1 from archive_job_items i where i.job_id = j.id and i.status = 'running') "
                        + "order by j.priority, j.created_at limit 1 for update skip locked",
                rs -> rs.next() ? mapJob(rs) : null);
    }

    void markJobRunning(String jobId) {
        jdbc.update("update archive_jobs set status = 'running', stage = 'METADATA', "
                + "started_at = coalesce(started_at, now()) where id = ?", UUID.fromString(jobId));
    }

    ArchiveModels.JobItem nextQueuedItem(String jobId) {
        return jdbc.query("select * from archive_job_items i where i.job_id = ? and i.status = 'queued' "
                        + "and i.next_attempt_at <= now() "
                        + "and not exists (select 1 from archive_job_items other where other.video_id = i.video_id "
                        + "and other.status = 'running' and other.job_id <> i.job_id) "
                        + "order by i.id limit 1 for update skip locked",
                rs -> rs.next() ? mapItem(rs) : null, UUID.fromString(jobId));
    }

    void markItemRunning(long itemId) {
        jdbc.update("update archive_job_items set status = 'running', attempts = attempts + 1 where id = ?", itemId);
        jdbc.update("update archive_jobs set current_video_id = (select video_id from archive_job_items where id = ?), "
                + "stage = 'METADATA', bytes_downloaded = 0, total_bytes = 0, speed_bps = 0 "
                + "where id = (select job_id from archive_job_items where id = ?)", itemId, itemId);
    }

    void updateMetadata(String jobId, String title, long estimatedBytes, Integer width, Integer height,
            String videoCodec, String audioCodec) {
        jdbc.update("update archive_jobs set title = ?, total_bytes = ?, width = ?, height = ?, "
                + "video_codec = ?, audio_codec = ? where id = ?", title, estimatedBytes, width, height,
                videoCodec, audioCodec, UUID.fromString(jobId));
    }

    void updateProgress(String jobId, ArchiveModels.Stage stage, long downloaded, long total, long speed) {
        jdbc.update("update archive_jobs set stage = ?, bytes_downloaded = ?, "
                + "total_bytes = case when ? > 0 then ? else total_bytes end, speed_bps = ? where id = ?",
                stage.name(), Math.max(0, downloaded), total, total, Math.max(0, speed), UUID.fromString(jobId));
    }

    void completeItem(long itemId, boolean success, String error, long nextDelaySeconds) {
        jdbc.update("update archive_job_items set status = ?, error = ?, next_attempt_at = now() + (? * interval '1 second') "
                        + "where id = ?", success ? "completed" : "failed", error, nextDelaySeconds, itemId);
        if (success) {
            jdbc.update("update archive_jobs set completed_count = completed_count + 1, "
                    + "stage = case when completed_count + 1 >= total_count then 'COMPLETED' else 'QUEUED' end, "
                    + "download_url = case when kind = 'VIDEO' and completed_count + 1 >= total_count "
                    + "then '/api/v1/library/' || target_id || '/file' else download_url end, speed_bps = 0 where id = "
                    + "(select job_id from archive_job_items where id = ?)", itemId);
        } else {
            jdbc.update("update archive_jobs set failed_count = failed_count + 1, "
                    + "stage = case when completed_count + failed_count + 1 >= total_count then 'FAILED' else 'QUEUED' end "
                    + "where id = (select job_id from archive_job_items where id = ?)", itemId);
            jdbc.update("update archive_jobs set error = ? where id = "
                    + "(select job_id from archive_job_items where id = ?)", error, itemId);
        }
    }

    void completeDownload(String jobId, String title, String path, String filename, boolean temporary,
            ArchiveModels.DownloadedFile file) {
        jdbc.update("update archive_job_items set status = 'completed', error = null where job_id = ?", UUID.fromString(jobId));
        jdbc.update("update archive_jobs set status = 'completed', stage = 'COMPLETED', completed_count = 1, "
                + "finished_at = now(), download_url = '/api/v1/archive/jobs/' || id || '/file', "
                + "file_path = ?, file_name = ?, temporary_file = ?, bytes_downloaded = ?, total_bytes = ?, speed_bps = 0, "
                + "title = ?, width = ?, height = ?, video_codec = ?, audio_codec = ? where id = ?",
                path, filename, temporary, file.sizeBytes(), file.sizeBytes(), title, file.width(), file.height(),
                file.videoCodec(), file.audioCodec(), UUID.fromString(jobId));
    }

    JobFile file(String jobId) {
        return jdbc.query("select file_path, file_name from archive_jobs where id = ? and download_url is not null",
                rs -> rs.next() && rs.getString("file_path") != null
                        ? new JobFile(Path.of(rs.getString("file_path")), rs.getString("file_name")) : null,
                UUID.fromString(jobId));
    }

    List<ExpiredFile> expiredTemporaryFiles() {
        return jdbc.query("select id, file_path from archive_jobs where temporary_file = true "
                        + "and download_url is not null and finished_at < now() - interval '24 hours'",
                (rs, rowNum) -> new ExpiredFile(rs.getString("id"), Path.of(rs.getString("file_path"))));
    }

    void removeExpiredFile(String jobId) {
        jdbc.update("update archive_jobs set stage = 'EXPIRED', download_url = null, file_path = null, "
                + "file_name = null, temporary_file = false where id = ?", UUID.fromString(jobId));
    }

    void recoverInterruptedJobs() {
        jdbc.update("update archive_job_items set status = 'queued', next_attempt_at = now() where status = 'running'");
        jdbc.update("update archive_jobs set status = 'queued', stage = 'QUEUED', speed_bps = 0, finished_at = null "
                + "where status = 'running' and exists (select 1 from archive_job_items "
                + "where job_id = archive_jobs.id and status = 'queued')");
        jdbc.update("update archive_jobs set status = case when failed_count = 0 then 'completed' else 'failed' end, "
                + "finished_at = now() where status = 'running' and completed_count + failed_count >= total_count");
    }

    void finishJob(String jobId) {
        jdbc.update("update archive_jobs set status = case when failed_count = 0 then 'completed' else 'failed' end, "
                + "error = case when failed_count = 0 then null else error end, "
                + "finished_at = now() where id = ? and completed_count + failed_count >= total_count", UUID.fromString(jobId));
    }

    void deferItem(long itemId, long delaySeconds, String error) {
        jdbc.update("update archive_job_items set status = 'queued', error = ?, "
                + "next_attempt_at = now() + (? * interval '1 second') where id = ?", error, delaySeconds, itemId);
        jdbc.update("update archive_jobs set error = ?, stage = 'WAITING', speed_bps = 0 where id = "
                + "(select job_id from archive_job_items where id = ?)", error, itemId);
    }

    List<ArchiveModels.Job> jobs(String owner, boolean admin, String status) {
        StringBuilder sql = new StringBuilder("select * from archive_jobs where true");
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        if (!admin) {
            sql.append(" and owner_id = ?");
            parameters.add(owner);
        }
        if (status != null && !status.isBlank()) {
            if (!List.of("queued", "running", "completed", "failed").contains(status)) {
                throw new IllegalArgumentException("Unknown archive job status");
            }
            sql.append(" and status = ?");
            parameters.add(status);
        }
        sql.append(" order by priority, created_at");
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapJob(rs), parameters.toArray());
    }

    long waitingAhead(String username) {
        QueueMarker first = jdbc.query("select id, priority, created_at from archive_jobs "
                        + "where owner_id = ? and status = 'queued' order by priority, created_at limit 1",
                rs -> rs.next() ? new QueueMarker(rs.getString("id"), rs.getInt("priority"),
                        rs.getTimestamp("created_at")) : null, username);
        if (first == null) {
            return 0;
        }
        Long count = jdbc.queryForObject("select count(*) from archive_job_items i join archive_jobs j on j.id = i.job_id "
                        + "where j.owner_id <> ? and j.status in ('queued', 'running') and i.status in ('queued', 'running') "
                        + "and (j.priority < ? or (j.priority = ? and j.created_at < ?))", Long.class,
                username, first.priority(), first.priority(), first.createdAt());
        return count == null ? 0 : count;
    }

    ArchiveModels.Job job(String jobId) {
        return jdbc.query("select * from archive_jobs where id = ?",
                rs -> rs.next() ? mapJob(rs) : null, UUID.fromString(jobId));
    }

    private ArchiveModels.Job mapJob(ResultSet rs) throws SQLException {
        String kind = rs.getString("kind");
        return new ArchiveModels.Job(rs.getString("id"), ArchiveModels.Kind.valueOf(kind),
                rs.getString("target_id"), rs.getString("owner_id"), rs.getString("owner_role"),
                rs.getInt("priority"), rs.getString("status"), rs.getInt("total_count"),
                rs.getInt("completed_count"), rs.getInt("failed_count"), rs.getString("created_at"),
                rs.getString("started_at"), rs.getString("finished_at"), rs.getString("next_attempt_at"),
                rs.getString("error"), "DOWNLOAD".equals(kind) ? ArchiveModels.Purpose.DOWNLOAD : ArchiveModels.Purpose.ARCHIVE,
                ArchiveModels.Stage.valueOf(rs.getString("stage")), rs.getLong("bytes_downloaded"),
                rs.getLong("total_bytes"), rs.getLong("speed_bps"), rs.getString("download_url"),
                rs.getString("title") == null ? rs.getString("target_id") : rs.getString("title"),
                rs.getObject("width", Integer.class), rs.getObject("height", Integer.class),
                rs.getString("video_codec"), rs.getString("audio_codec"), options(rs.getString("options_key")),
                rs.getString("current_video_id"), rs.getString("file_name"));
    }

    private static ArchiveModels.DownloadOptions options(String signature) {
        if (signature == null) {
            return ArchiveModels.DownloadOptions.archiveDefaults();
        }
        String[] parts = signature.split("\\|", -1);
        return new ArchiveModels.DownloadOptions("null".equals(parts[0]) ? null : parts[0],
                "null".equals(parts[1]) ? null : parts[1],
                Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]));
    }

    private ArchiveModels.JobItem mapItem(ResultSet rs) throws SQLException {
        return new ArchiveModels.JobItem(rs.getLong("id"), rs.getString("job_id"), rs.getString("video_id"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("next_attempt_at"), rs.getString("error"));
    }

    record JobFile(Path path, String filename) {
    }

    record ExpiredFile(String jobId, Path path) {
    }

    private record QueueMarker(String id, int priority, Timestamp createdAt) {
    }
}
