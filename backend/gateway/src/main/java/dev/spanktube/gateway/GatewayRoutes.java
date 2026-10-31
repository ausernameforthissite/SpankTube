package dev.spanktube.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GatewayRoutes {

    @Bean
    RouteLocator stageOneRoutes(RouteLocatorBuilder routes, GatewayProperties properties) {
        return routes.routes()
                .route("user-service", route -> route.path("/api/v1/auth/**")
                        .uri(properties.userServiceUrl()))
                .route("youtube-upstream-service", route -> route.path("/api/v1/videos/**")
                        .uri(properties.upstreamServiceUrl()))
                .route("media-service", route -> route.path("/media/**")
                        .uri(properties.mediaServiceUrl()))
                .build();
    }
}
