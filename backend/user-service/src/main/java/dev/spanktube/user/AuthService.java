package dev.spanktube.user;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class AuthService {

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JdbcTemplate jdbc;

    AuthService(AuthProperties properties, PasswordEncoder passwordEncoder,
            JwtService jwtService, RefreshTokenService refreshTokenService, JdbcTemplate jdbc) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jdbc = jdbc;
    }

    TokenPair login(String username, String password) {
        AuthProperties.Account account = account(username);
        if (account == null || password == null || account.passwordHash() == null || account.passwordHash().isBlank()
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return issueTokens(username, account.role());
    }

    TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is required");
        }
        String username = refreshTokenService.rotate(refreshToken);
        AuthProperties.Account account = account(username);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists");
        }
        return issueTokens(username, account.role());
    }

    TokenPair issueTokens(String username, AuthProperties.Role role) {
        JwtService.IssuedAccessToken access = jwtService.issue(username, role);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(username);
        return new TokenPair(access.value(), "Bearer", access.expiresIn(),
                refresh.value(), refresh.expiresIn(), role);
    }

    private AuthProperties.Account account(String username) {
        if (username == null) {
            return null;
        }
        AuthProperties.Account configured = properties.users().get(username);
        if (configured != null) {
            return configured;
        }
        return jdbc.query("select password_hash, role from local_users where username = ?", rs -> {
            if (!rs.next()) {
                return null;
            }
            return new AuthProperties.Account(rs.getString("password_hash"),
                    AuthProperties.Role.valueOf(rs.getString("role")));
        }, username);
    }

    record TokenPair(String accessToken, String tokenType, long expiresIn,
            String refreshToken, long refreshExpiresIn, AuthProperties.Role role) {
    }
}
