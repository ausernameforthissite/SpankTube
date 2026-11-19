package dev.spanktube.user;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalFileAccessServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QuotaService quotas = mock(QuotaService.class);

    @Test
    void administratorHasImplicitAccessWithoutGrant() {
        LocalFileAccessService service = service(Map.of("admin", AuthProperties.Role.ADMIN));

        assertThat(service.access("admin").enabled()).isTrue();
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), eq("admin"));
    }

    @Test
    void viewerRequiresExplicitGrant() {
        LocalFileAccessService service = service(Map.of("viewer", AuthProperties.Role.VIEWER));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("viewer")))
                .thenThrow(new EmptyResultDataAccessException(1))
                .thenReturn(true);

        assertThat(service.access("viewer").enabled()).isFalse();
        assertThat(service.access("viewer").enabled()).isTrue();
    }

    @Test
    void updatePersistsGrantAndKnownUsersIncludeRegisteredAccounts() {
        AuthProperties properties = properties(Map.of("admin", AuthProperties.Role.ADMIN));
        LocalFileAccessService service = new LocalFileAccessService(jdbc, quotas, properties);
        when(quotas.resolve("viewer")).thenReturn(user("viewer", AuthProperties.Role.VIEWER));
        when(jdbc.queryForList("select username from local_users", String.class))
                .thenReturn(List.of("viewer", "admin"));

        assertThat(service.update("viewer", true).enabled()).isTrue();
        verify(jdbc).update("insert into user_settings(username, local_files_access) values (?, ?) "
                + "on conflict(username) do update set local_files_access = excluded.local_files_access",
                "viewer", true);
        assertThat(service.knownUsernames()).containsExactly("admin", "viewer");
    }

    private LocalFileAccessService service(Map<String, AuthProperties.Role> users) {
        AuthProperties properties = properties(users);
        for (var user : users.entrySet()) {
            when(quotas.resolve(user.getKey())).thenReturn(user(user.getKey(), user.getValue()));
        }
        return new LocalFileAccessService(jdbc, quotas, properties);
    }

    private static AuthProperties properties(Map<String, AuthProperties.Role> users) {
        return new AuthProperties("spanktube", null, null, Duration.ofMinutes(15), Duration.ofDays(30),
                users.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> new AuthProperties.Account("hash", entry.getValue()))));
    }

    private static QuotaService.UserQuota user(String username, AuthProperties.Role role) {
        return new QuotaService.UserQuota(username, role, null, 10L, 90);
    }
}
