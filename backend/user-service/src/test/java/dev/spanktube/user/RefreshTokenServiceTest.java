package dev.spanktube.user;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthProperties properties = new AuthProperties("spanktube", null, null,
            Duration.ofMinutes(15), Duration.ofDays(365), Map.of());
    private final RefreshTokenService service = new RefreshTokenService(properties, jdbc);

    @Test
    void storesOnlyTokenHashWithOneYearExpiration() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> times = ArgumentCaptor.forClass(Timestamp.class);

        RefreshTokenService.IssuedRefreshToken issued = service.issue("viewer");

        verify(jdbc).update(contains("insert into refresh_sessions"), hash.capture(), eq("viewer"),
                times.capture(), times.capture(), times.capture());
        assertThat(hash.getValue()).matches("[0-9a-f]{64}").isNotEqualTo(issued.value());
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofDays(365).toSeconds());
        assertThat(times.getAllValues().get(2).toInstant().minus(Duration.ofDays(365)))
                .isEqualTo(times.getAllValues().get(0).toInstant());
    }

    @Test
    void rotatesValidPersistedToken() {
        session(Instant.now().plus(Duration.ofDays(10)));
        when(jdbc.update(contains("delete from refresh_sessions where token_hash = ?"), anyString(), any(Timestamp.class)))
                .thenReturn(1);

        assertThat(service.rotate("raw-token")).isEqualTo("viewer");

        verify(jdbc).update(contains("delete from refresh_sessions where token_hash = ?"), anyString(), any(Timestamp.class));
    }

    @Test
    void rejectsExpiredPersistedTokenWithoutConsumingIt() {
        session(Instant.now().minusSeconds(1));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.rotate("raw-token"));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(jdbc, never()).update(contains("delete from refresh_sessions where token_hash = ?"), anyString(), any(Timestamp.class));
    }

    @Test
    void activitySlidesExpirationByOneYear() {
        ArgumentCaptor<Timestamp> times = ArgumentCaptor.forClass(Timestamp.class);
        when(jdbc.update(contains("set last_used_at = ?"), any(Timestamp.class), any(Timestamp.class),
                anyString(), any(Timestamp.class))).thenReturn(1);

        service.touch("raw-token");

        verify(jdbc).update(contains("set last_used_at = ?"), times.capture(), times.capture(), anyString(), times.capture());
        assertThat(times.getAllValues().get(1).toInstant().minus(Duration.ofDays(365)))
                .isEqualTo(times.getAllValues().get(0).toInstant());
    }

    @Test
    void activityRejectsUnknownToken() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.touch("unknown"));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void session(Instant expiresAt) {
        when(jdbc.query(contains("select username, expires_at from refresh_sessions"), any(ResultSetExtractor.class), anyString()))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(true);
                    when(rs.getString("username")).thenReturn("viewer");
                    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(expiresAt));
                    return invocation.getArgument(1, ResultSetExtractor.class).extractData(rs);
                });
    }
}
