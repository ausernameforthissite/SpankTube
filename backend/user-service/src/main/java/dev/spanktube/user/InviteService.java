package dev.spanktube.user;

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

    private final JdbcTemplate jdbc;
    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    InviteService(JdbcTemplate jdbc, AuthProperties properties, PasswordEncoder passwordEncoder, AuthService authService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    Invite create(String administrator) {
        String code = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
        jdbc.update("insert into invite_tokens(code, created_by, expires_at) values (?, ?, ?)", code, administrator, expiresAt);
        return new Invite(code, expiresAt.toString(), null);
    }

    List<Invite> list(String administrator) {
        return jdbc.query("select code, expires_at, used_by from invite_tokens where created_by = ? order by created_at desc",
                (rs, rowNum) -> new Invite(rs.getString("code"), rs.getString("expires_at"), rs.getString("used_by")),
                administrator);
    }

    @Transactional
    public AuthService.TokenPair register(String code, String username, String password) {
        if (code == null || code.isBlank() || username == null || !username.matches("[A-Za-z0-9_.-]{3,32}")
                || password == null || password.length() < 8 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite, username or password");
        }
        if (properties.users().containsKey(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        int claimed = jdbc.update("update invite_tokens set used_by = ?, used_at = now() "
                + "where code = ? and used_by is null and expires_at > now()", username, code);
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite is invalid, used or expired");
        }
        jdbc.update("insert into local_users(username, password_hash, role) values (?, ?, 'VIEWER')",
                username, passwordEncoder.encode(password));
        return authService.issueTokens(username, AuthProperties.Role.VIEWER);
    }

    record Invite(String code, String expiresAt, String usedBy) {
    }
}
