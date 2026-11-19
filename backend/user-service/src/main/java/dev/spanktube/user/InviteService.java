package dev.spanktube.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class InviteService {

    private static final String REGISTRATION = "REGISTRATION";
    private static final String PASSWORD_RESET = "PASSWORD_RESET";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    InviteService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuthService authService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    Invite create(String administrator) {
        String code = newCode();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
        jdbc.update("insert into invite_tokens(code, created_by, expires_at) values (?, ?, ?)",
                code, administrator, Timestamp.from(expiresAt));
        return new Invite(code, expiresAt.toString(), null);
    }

    PasswordResetInvite createPasswordReset(String administrator, String username) {
        if (!authService.accountExists(username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        String code = newCode();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));
        jdbc.update("insert into invite_tokens(code, created_by, expires_at, used_by, kind, target_username) "
                + "values (?, ?, ?, null, ?, ?)", code, administrator, Timestamp.from(expiresAt), PASSWORD_RESET, username);
        return new PasswordResetInvite(code, expiresAt.toString(), username);
    }

    List<Invite> list(String administrator) {
        return jdbc.query("select code, expires_at, used_by from invite_tokens "
                        + "where created_by = ? and kind = 'REGISTRATION' order by created_at desc",
                (rs, rowNum) -> new Invite(rs.getString("code"), rs.getString("expires_at"), rs.getString("used_by")),
                administrator);
    }

    @Transactional
    public AuthService.TokenPair register(String code, String username, String password) {
        if (code == null || code.isBlank() || username == null || !username.matches("[A-Za-z0-9_.-]{3,32}")
                || password == null || password.length() < 8 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite, username or password");
        }
        InviteState invite = find(code, REGISTRATION);
        if (invite == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This invite is invalid");
        }
        if (!invite.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This invite has expired. Ask an administrator for a new invite");
        }
        if (invite.usedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invite has already been used");
        }
        if (authService.accountExists(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        int claimed = jdbc.update("update invite_tokens set used_by = ?, used_at = now() "
                + "where code = ? and kind = 'REGISTRATION' and used_at is null and expires_at > now()", username, code);
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This invite is no longer available");
        }
        jdbc.update("insert into local_users(username, password_hash, role) values (?, ?, 'VIEWER')",
                username, passwordEncoder.encode(password));
        return authService.issueTokens(username, AuthProperties.Role.VIEWER);
    }

    @Transactional
    public void resetPassword(String code, String password) {
        if (code == null || code.isBlank() || password == null || password.length() < 8 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid invite and a password of 8-128 characters are required");
        }
        InviteState invite = find(code, PASSWORD_RESET);
        if (invite == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This password reset invite is invalid");
        }
        if (!invite.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This password reset invite has expired. Ask an administrator for a new one");
        }
        if (invite.usedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This password reset invite has already been used");
        }
        int claimed = jdbc.update("update invite_tokens set used_by = target_username, used_at = now() "
                + "where code = ? and kind = 'PASSWORD_RESET' and used_at is null and expires_at > now()", code);
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This password reset invite is no longer available");
        }
        authService.resetPassword(invite.targetUsername(), password);
    }

    private InviteState find(String code, String kind) {
        return jdbc.query("select expires_at, used_at, target_username from invite_tokens where code = ? and kind = ?",
                rs -> {
                    if (!rs.next()) return null;
                    var usedAt = rs.getTimestamp("used_at");
                    return new InviteState(rs.getTimestamp("expires_at").toInstant(),
                            usedAt == null ? null : usedAt.toInstant(), rs.getString("target_username"));
                }, code, kind);
    }

    private static String newCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    record Invite(String code, String expiresAt, String usedBy) {
    }

    record PasswordResetInvite(String code, String expiresAt, String username) {
    }

    record InviteState(Instant expiresAt, Instant usedAt, String targetUsername) {
    }
}
