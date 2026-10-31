package dev.spanktube.user;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spanktube.auth")
public record AuthProperties(
        String issuer,
        String privateKey,
        String publicKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Map<String, Account> users) {

    public AuthProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "spanktube";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
        if (users == null) {
            users = Map.of();
        }
    }

    public record Account(String passwordHash, Role role) {
    }

    public enum Role {
        VIEWER,
        ADMIN;

        public String authority() {
            return "ROLE_" + name();
        }
    }
}
