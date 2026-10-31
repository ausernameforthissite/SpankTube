package dev.spanktube.gateway;

import java.util.Collection;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
class IdentityForwardingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-SpankTube-User");
                    headers.remove("X-SpankTube-Roles");
                })
                .build();
        ServerWebExchange cleanExchange = exchange.mutate().request(sanitized).build();
        return cleanExchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .flatMap(authentication -> {
                    ServerHttpRequest identified = cleanExchange.getRequest().mutate()
                            .header("X-SpankTube-User", authentication.getToken().getSubject())
                            .header("X-SpankTube-Roles", roles(authentication))
                            .build();
                    return chain.filter(cleanExchange.mutate().request(identified).build());
                })
                .switchIfEmpty(Mono.defer(() -> chain.filter(cleanExchange)));
    }

    private static String roles(JwtAuthenticationToken authentication) {
        Object roles = authentication.getToken().getClaims().get("roles");
        return roles instanceof Collection<?> values
                ? String.join(",", values.stream().map(String::valueOf).toList())
                : "";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
