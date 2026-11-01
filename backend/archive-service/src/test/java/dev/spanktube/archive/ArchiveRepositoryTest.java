package dev.spanktube.archive;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ArchiveRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ArchiveRepository repository = new ArchiveRepository(jdbc);

    @Test
    void insertsJobIdsAsUuid() {
        repository.createJob(new ArchiveModels.EnqueueRequest("admin", "ADMIN",
                ArchiveModels.Kind.VIDEO, "3TYZjVzWazE", List.of("3TYZjVzWazE")));

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into archive_jobs"), parameters.capture());
        assertThat(parameters.getValue()[0]).isInstanceOf(UUID.class);
        assertThat(parameters.getValue()).contains("admin", "ADMIN", "VIDEO", "3TYZjVzWazE", 0, 1);
        verify(jdbc).update(contains("insert into archive_job_items"), any(Object[].class));
    }

    @Test
    void nextJobIncludesDelayedItemsAndPreservesAdminPriority() {
        repository.nextQueuedJob();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.ResultSetExtractor.class));
        assertThat(sql.getValue()).contains("j.status in ('queued', 'running')")
                .contains("i.status = 'queued'")
                .contains("order by j.priority, j.created_at");
    }
}
