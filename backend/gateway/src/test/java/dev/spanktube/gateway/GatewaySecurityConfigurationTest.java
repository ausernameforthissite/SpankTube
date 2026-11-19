package dev.spanktube.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebFluxTest
@Import({GatewaySecurityConfiguration.class, GatewaySecurityConfigurationTest.AuthController.class})
class GatewaySecurityConfigurationTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private GatewayProperties properties;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void passwordResetIsPublic() {
        client.post().uri("/api/v1/auth/password-reset")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void passwordResetIssuanceRequiresAuthentication() {
        client.post().uri("/api/v1/admin/users/mda/password-reset-invite")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @RestController
    static class AuthController {
        @PostMapping("/api/v1/auth/password-reset")
        ResponseEntity<Void> reset() {
            return ResponseEntity.noContent().build();
        }
    }
}
