package dev.spanktube.catalog;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CatalogRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CatalogRepository repository = new CatalogRepository(jdbc);

    @Test
    void updateProgressUpsertsUserPosition() {
        repository.updateProgress("viewer", "video", 42, 25);

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into user_views"), parameters.capture());
        assertThat(parameters.getValue()).contains("viewer", "video", 42L, 25.0);
        verify(jdbc).update(contains("on conflict(user_id, video_id) do update"), any(Object[].class));
    }

    @Test
    void progressUsesPerUserTimeline() {
        repository.progress("viewer");

        verify(jdbc).query(contains("last_position_sec"), any(org.springframework.jdbc.core.RowMapper.class), eq("viewer"));
    }
}
