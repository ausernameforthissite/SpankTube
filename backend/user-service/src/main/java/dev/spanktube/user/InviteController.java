package dev.spanktube.user;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InviteController {

    private final InviteService invites;

    InviteController(InviteService invites) {
        this.invites = invites;
    }

    @PostMapping("/api/v1/invites")
    @PreAuthorize("hasRole('ADMIN')")
    InviteService.Invite create(@AuthenticationPrincipal Jwt jwt) {
        return invites.create(jwt.getSubject());
    }

    @GetMapping("/api/v1/invites")
    @PreAuthorize("hasRole('ADMIN')")
    List<InviteService.Invite> list(@AuthenticationPrincipal Jwt jwt) {
        return invites.list(jwt.getSubject());
    }

    @PostMapping("/api/v1/auth/register")
    AuthService.TokenPair register(@RequestBody RegistrationRequest request) {
        return invites.register(request.invite(), request.username(), request.password());
    }

    @PostMapping("/api/v1/admin/users/{username}/password-reset-invite")
    @PreAuthorize("hasRole('ADMIN')")
    InviteService.PasswordResetInvite createPasswordReset(@AuthenticationPrincipal Jwt jwt, @PathVariable String username) {
        return invites.createPasswordReset(jwt.getSubject(), username);
    }

    @PostMapping("/api/v1/auth/password-reset")
    ResponseEntity<Void> resetPassword(@RequestBody PasswordResetRequest request) {
        invites.resetPassword(request.invite(), request.password());
        return ResponseEntity.noContent().build();
    }

    record RegistrationRequest(String invite, String username, String password) {
    }

    record PasswordResetRequest(String invite, String password) {
    }
}
