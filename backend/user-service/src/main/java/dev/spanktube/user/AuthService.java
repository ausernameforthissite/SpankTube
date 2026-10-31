package dev.spanktube.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class AuthService {

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    AuthService(AuthProperties properties, PasswordEncoder passwordEncoder,
            JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    TokenPair login(String username, String password) {
        AuthProperties.Account account = username == null ? null : properties.users().get(username);
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
        AuthProperties.Account account = properties.users().get(username);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists");
        }
        return issueTokens(username, account.role());
    }

    private TokenPair issueTokens(String username, AuthProperties.Role role) {
        JwtService.IssuedAccessToken access = jwtService.issue(username, role);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(username);
        return new TokenPair(access.value(), "Bearer", access.expiresIn(),
                refresh.value(), refresh.expiresIn(), role);
    }

    record TokenPair(String accessToken, String tokenType, long expiresIn,
            String refreshToken, long refreshExpiresIn, AuthProperties.Role role) {
    }
}
