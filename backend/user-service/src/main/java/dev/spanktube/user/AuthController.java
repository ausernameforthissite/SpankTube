package dev.spanktube.user;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    AuthService.TokenPair login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    AuthService.TokenPair refresh(@RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    record LoginRequest(String username, String password) {
    }

    record RefreshRequest(String refreshToken) {
    }
}
