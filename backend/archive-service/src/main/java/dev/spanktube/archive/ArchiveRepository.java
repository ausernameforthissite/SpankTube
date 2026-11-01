package dev.spanktube.archive;

import java.sql.ResultSet;
import java.sql.SQLException;
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
        jdbc.update("insert into archive_jobs(id, owner_id, owner_role, kind, target_id, priority, status, total_count) "
                        + "values (?, ?, ?, ?, ?, ?, 'queued', ?)",
                id, request.owner(), request.role(), request.kind().name(), request.targetId(), priority, request.videoIds().size());
        request.videoIds().forEach(videoId -> jdbc.update(
                "insert into archive_job_items(job_id, video_id, status) values (?, ?, 'queued') "
                        + "on conflict(job_id, video_id) do nothing", id, videoId));
        return id.toString();
    }

    String activeVideoJob(String videoId, String owner) {
        return jdbc.query("select j.id from archive_job_items i join archive_jobs j on j.id = i.job_id "
                        + "where i.video_id = ? and j.owner_id = ? "
                        + "and i.status in ('queued', 'running') and j.status in ('queued', 'running') "
                        + "order by j.created_at desc limit 1",
                rs -> rs.next() ? rs.getString(1) : null, videoId, owner);
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
        jdbc.update("update archive_jobs set status = 'running', started_at = coalesce(started_at, now()) where id = ?", UUID.fromString(jobId));
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
    }

    void completeItem(long itemId, boolean success, String error, long nextDelaySeconds) {
        jdbc.update("update archive_job_items set status = ?, error = ?, next_attempt_at = now() + (? * interval '1 second') "
                        + "where id = ?", success ? "completed" : "failed", error, nextDelaySeconds, itemId);
        if (success) {
            jdbc.update("update archive_jobs set completed_count = completed_count + 1 where id = "
                    + "(select job_id from archive_job_items where id = ?)", itemId);
        } else {
            jdbc.update("update archive_jobs set failed_count = failed_count + 1 where id = "
                    + "(select job_id from archive_job_items where id = ?)", itemId);
            jdbc.update("update archive_jobs set error = ? where id = "
                    + "(select job_id from archive_job_items where id = ?)", error, itemId);
        }
    }

    void recoverInterruptedJobs() {
        jdbc.update("update archive_job_items set status = 'queued', next_attempt_at = now() where status = 'running'");
        jdbc.update("update archive_jobs set status = 'queued', finished_at = null where status = 'running' "
                + "and exists (select 1 from archive_job_items where job_id = archive_jobs.id and status = 'queued')");
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
        jdbc.update("update archive_jobs set error = ? where id = "
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

    ArchiveModels.Job job(String jobId) {
        return jdbc.query("select * from archive_jobs where id = ?",
                rs -> rs.next() ? mapJob(rs) : null, UUID.fromString(jobId));
    }

    private ArchiveModels.Job mapJob(ResultSet rs) throws SQLException {
        return new ArchiveModels.Job(rs.getString("id"), ArchiveModels.Kind.valueOf(rs.getString("kind")),
                rs.getString("target_id"), rs.getString("owner_id"), rs.getString("owner_role"),
                rs.getInt("priority"), rs.getString("status"), rs.getInt("total_count"),
                rs.getInt("completed_count"), rs.getInt("failed_count"), rs.getString("created_at"),
                rs.getString("started_at"), rs.getString("finished_at"), rs.getString("next_attempt_at"),
                rs.getString("error"));
    }

    private ArchiveModels.JobItem mapItem(ResultSet rs) throws SQLException {
        return new ArchiveModels.JobItem(rs.getLong("id"), rs.getString("job_id"), rs.getString("video_id"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("next_attempt_at"), rs.getString("error"));
    }
}
