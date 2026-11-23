package dev.spanktube.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class RefreshTokenService {

    private final SecureRandom random = new SecureRandom();
    private final AuthProperties properties;
    private final JdbcTemplate jdbc;

    RefreshTokenService(AuthProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    IssuedRefreshToken issue(String username) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        jdbc.update("delete from refresh_sessions where expires_at <= ?", Timestamp.from(now));
        jdbc.update("insert into refresh_sessions(token_hash, username, created_at, last_used_at, expires_at) "
                        + "values (?, ?, ?, ?, ?)", hash(value), username, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(expiresAt));
        return new IssuedRefreshToken(value, properties.refreshTokenTtl().toSeconds());
    }

    @Transactional
    public String rotate(String token) {
        Instant now = Instant.now();
        Session session = jdbc.query("select username, expires_at from refresh_sessions where token_hash = ?",
                rs -> rs.next() ? new Session(rs.getString("username"), rs.getTimestamp("expires_at").toInstant()) : null,
                hash(token));
        if (session == null || !session.expiresAt().isAfter(now)
                || jdbc.update("delete from refresh_sessions where token_hash = ? and expires_at > ?",
                        hash(token), Timestamp.from(now)) != 1) {
            throw invalid();
        }
        return session.username();
    }

    @Transactional
    public void touch(String token) {
        Instant now = Instant.now();
        int updated = jdbc.update("update refresh_sessions set last_used_at = ?, expires_at = ? "
                        + "where token_hash = ? and expires_at > ?", Timestamp.from(now),
                Timestamp.from(now.plus(properties.refreshTokenTtl())), hash(token), Timestamp.from(now));
        if (updated != 1) {
            throw invalid();
        }
    }

    void revoke(String token) {
        if (token != null && !token.isBlank()) {
            jdbc.update("delete from refresh_sessions where token_hash = ?", hash(token));
        }
    }

    void revokeUser(String username) {
        jdbc.update("delete from refresh_sessions where username = ?", username);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
    }

    private static String hash(String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Session(String username, Instant expiresAt) {
    }

    record IssuedRefreshToken(String value, long expiresIn) {
    }
}
