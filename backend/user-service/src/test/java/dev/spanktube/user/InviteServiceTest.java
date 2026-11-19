package dev.spanktube.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthService auth = mock(AuthService.class);
    private final InviteService service = new InviteService(jdbc, passwords, auth);

    @Test
    void expiredRegistrationInviteReturnsClearGoneResponse() {
        invite("expired", "REGISTRATION", new InviteService.InviteState(
                Instant.now().minusSeconds(60), null, null));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.register("expired", "new_user", "new-password"));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(error.getReason()).isEqualTo("This invite has expired. Ask an administrator for a new invite");
    }

    @Test
    void passwordResetInviteCannotRegisterAnotherAccount() {
        invite("reset", "REGISTRATION", null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.register("reset", "new_user", "new-password"));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getReason()).isEqualTo("This invite is invalid");
    }

    @Test
    void resetConsumesInviteForItsBoundAccount() {
        invite("reset", "PASSWORD_RESET", new InviteService.InviteState(
                Instant.now().plusSeconds(600), null, "mda"));
        when(jdbc.update(anyString(), eq("reset"))).thenReturn(1);

        service.resetPassword("reset", "new-password");

        verify(auth).resetPassword("mda", "new-password");
    }

    @Test
    void registrationInviteUsesJdbcTimestamp() {
        InviteService.Invite invite = service.create("admin");

        verify(jdbc).update(anyString(), eq(invite.code()), eq("admin"), any(Timestamp.class));
    }

    @Test
    void administratorCreatesSingleUseResetInviteForOneHour() {
        when(auth.accountExists("mda")).thenReturn(true);

        InviteService.PasswordResetInvite invite = service.createPasswordReset("admin", "mda");

        assertThat(invite.code()).matches("[0-9a-f]{32}");
        assertThat(invite.username()).isEqualTo("mda");
        assertThat(Instant.parse(invite.expiresAt())).isBetween(Instant.now().plus(Duration.ofMinutes(59)),
                Instant.now().plus(Duration.ofMinutes(61)));
        verify(jdbc).update(anyString(), eq(invite.code()), eq("admin"), any(Timestamp.class), eq("PASSWORD_RESET"), eq("mda"));
    }

    private void invite(String code, String kind, InviteService.InviteState state) {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<InviteService.InviteState>>any(),
                eq(code), eq(kind))).thenReturn(state);
    }
}
