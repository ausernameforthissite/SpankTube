package dev.spanktube.user;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void resetStoresPasswordOverrideAndRevokesRefreshSessions() {
        AuthProperties properties = new AuthProperties("spanktube", null, null,
                Duration.ofMinutes(15), Duration.ofDays(30),
                Map.of("admin", new AuthProperties.Account("configured-hash", AuthProperties.Role.ADMIN)));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("new-password")).thenReturn("encoded-password");
        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        AuthService service = new AuthService(properties, passwords, mock(JwtService.class), refreshTokens, jdbc);

        service.resetPassword("admin", "new-password");

        verify(jdbc).update("insert into local_users(username, password_hash, role) values (?, ?, ?) "
                        + "on conflict(username) do update set password_hash = excluded.password_hash, role = excluded.role",
                "admin", "encoded-password", "ADMIN");
        verify(refreshTokens).revokeUser("admin");
    }
}
