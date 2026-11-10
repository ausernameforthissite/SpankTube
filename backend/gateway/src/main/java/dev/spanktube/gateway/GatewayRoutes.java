package dev.spanktube.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GatewayRoutes {

    @Bean
    RouteLocator stageTwoRoutes(RouteLocatorBuilder routes, GatewayProperties properties) {
        return routes.routes()
                .route("auth-service", route -> route.path("/api/v1/auth/**")
                        .uri(properties.userServiceUrl()))
                .route("archive-video-recording", route -> route.path(
                        "/api/v1/videos/*/recording", "/api/v1/videos/*/recording/**")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-video-download", route -> route.path("/api/v1/videos/*/download")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-video-save", route -> route.path("/api/v1/videos/*/save")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-video-refresh", route -> route.path("/api/v1/videos/*/refresh")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-playlist-save", route -> route.path("/api/v1/playlists/*/save")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-channel-save", route -> route.path("/api/v1/channels/*/save")
                        .uri(properties.archiveServiceUrl()))
                .route("archive-jobs", route -> route.path("/api/v1/archive/**")
                        .uri(properties.archiveServiceUrl()))
                .route("user-settings", route -> route.path("/api/v1/users/**")
                        .uri(properties.userServiceUrl()))
                .route("admin-users", route -> route.path("/api/v1/admin/users/**")
                        .uri(properties.userServiceUrl()))
                .route("library", route -> route.path("/api/v1/library/**", "/api/v1/admin/library/**", "/api/v1/admin/storage/**")
                        .uri(properties.catalogServiceUrl()))
                .route("catalog-videos", route -> route.path("/api/v1/videos/**")
                        .uri(properties.catalogServiceUrl()))
                .route("upstream-search", route -> route.path("/api/v1/search", "/api/v1/channels/**",
                        "/api/v1/playlists/**", "/api/v1/metadata/logs")
                        .uri(properties.upstreamServiceUrl()))
                .route("media-service", route -> route.path("/media/**")
                        .uri(properties.mediaServiceUrl()))
                .build();
    }
}
