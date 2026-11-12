package dev.spanktube.gateway;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutesTest {

    @Test
    void estimateEventsDisableGlobalResponseTimeout() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GatewayProperties.class, () -> new GatewayProperties(
                    null, null, null, null, null, null, null));
            context.registerBean(RouteLocatorBuilder.class, () -> new RouteLocatorBuilder(context));
            context.register(WebFluxProperties.class);
            context.register(PathRoutePredicateFactory.class);
            context.register(GatewayRoutes.class);
            context.refresh();
            List<Route> routes = context.getBean(RouteLocator.class).getRoutes().collectList().block();

            assertThat(routes).isNotNull();
            assertThat(routes).filteredOn(route -> route.getId().equals("archive-estimate-events"))
                    .singleElement()
                    .satisfies(route -> assertThat(route.getMetadata())
                            .containsEntry(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, -1));
            assertThat(routes).filteredOn(route -> route.getId().equals("archive-jobs"))
                    .singleElement()
                    .satisfies(route -> assertThat(route.getMetadata())
                            .doesNotContainKey(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR));
        }
    }
}
