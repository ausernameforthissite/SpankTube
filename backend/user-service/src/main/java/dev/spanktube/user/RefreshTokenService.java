package dev.spanktube.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class RefreshTokenService {

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AuthProperties properties;

    RefreshTokenService(AuthProperties properties) {
        this.properties = properties;
    }

    IssuedRefreshToken issue(String username) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        Instant expiresAt = Instant.now().plus(properties.refreshTokenTtl());
        sessions.put(hash(value), new Session(username, expiresAt));
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return new IssuedRefreshToken(value, properties.refreshTokenTtl().toSeconds());
    }

    String rotate(String token) {
        Session session = sessions.remove(hash(token));
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }
        return session.username();
    }

    private static String hash(String token) {
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
