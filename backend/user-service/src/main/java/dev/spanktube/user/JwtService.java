package dev.spanktube.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
class JwtService {

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    JwtService(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    IssuedAccessToken issue(String username, AuthProperties.Role role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role.authority()))
                .build();
        JwsHeader header = JwsHeader.with(() -> JwsAlgorithms.RS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, properties.accessTokenTtl().toSeconds());
    }

    record IssuedAccessToken(String value, long expiresIn) {
    }
}
