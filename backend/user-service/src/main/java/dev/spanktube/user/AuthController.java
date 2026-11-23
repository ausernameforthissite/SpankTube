package dev.spanktube.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;
    private final SessionCookies cookies;

    AuthController(AuthService authService, SessionCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    AuthService.TokenPair login(@RequestBody LoginRequest request, HttpServletResponse response) {
        AuthService.TokenPair tokens = authService.login(request.username(), request.password());
        cookies.set(response, tokens.refreshToken());
        return tokens;
    }

    @PostMapping("/refresh")
    AuthService.TokenPair refresh(@RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        AuthService.TokenPair tokens = authService.refresh(token(request, httpRequest));
        cookies.set(response, tokens.refreshToken());
        return tokens;
    }

    @PostMapping("/activity")
    ResponseEntity<Void> activity(@RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        String token = token(request, httpRequest);
        authService.activity(token);
        cookies.set(response, token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        authService.logout(token(request, httpRequest));
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    private String token(RefreshRequest request, HttpServletRequest httpRequest) {
        String supplied = request == null ? null : request.refreshToken();
        return supplied == null || supplied.isBlank() ? cookies.read(httpRequest) : supplied;
    }

    record LoginRequest(String username, String password) {
    }

    record RefreshRequest(String refreshToken) {
    }
}
