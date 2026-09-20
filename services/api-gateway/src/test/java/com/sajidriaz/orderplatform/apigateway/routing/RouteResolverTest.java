package com.sajidriaz.orderplatform.apigateway.routing;

import com.sajidriaz.orderplatform.apigateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RouteResolverTest {

    private final RouteResolver resolver = new RouteResolver(properties(
            new GatewayProperties.Route("orders", "/api/v1/orders", "http://order-service:8080", false),
            new GatewayProperties.Route("order-summary", "/api/v1/orders/summary", "http://reporting:8080", false),
            new GatewayProperties.Route("auth-token", "/api/v1/auth/token",
                    "http://keycloak:8080/realms/order-platform/protocol/openid-connect/token", true)));

    @Test
    void forwardsToTheOwningServiceKeepingThePath() {
        RouteResolver.ResolvedRoute resolved = resolver.resolve("/api/v1/orders").orElseThrow();

        assertThat(resolved.route().id()).isEqualTo("orders");
        assertThat(resolved.downstreamUri()).isEqualTo("http://order-service:8080/api/v1/orders");
    }

    @Test
    void keepsPathVariables() {
        assertThat(resolver.resolve("/api/v1/orders/8f1b/status").orElseThrow().downstreamUri())
                .isEqualTo("http://order-service:8080/api/v1/orders/8f1b/status");
    }

    @Test
    void longestPrefixWinsSoAMoreSpecificRouteCanBeSplitOut() {
        RouteResolver.ResolvedRoute resolved = resolver.resolve("/api/v1/orders/summary").orElseThrow();

        assertThat(resolved.route().id()).isEqualTo("order-summary");
    }

    @Test
    void stripsThePrefixWhenTheRouteAsksForIt() {
        // The OIDC path is an identity-provider detail; the public contract is /api/v1/auth/token.
        assertThat(resolver.resolve("/api/v1/auth/token").orElseThrow().downstreamUri())
                .isEqualTo("http://keycloak:8080/realms/order-platform/protocol/openid-connect/token");
    }

    @Test
    void matchesWholeSegmentsOnly() {
        // A plain startsWith would hand this to order-service.
        assertThat(resolver.resolve("/api/v1/ordersearch")).isEmpty();
    }

    @Test
    void unroutedPathsResolveToNothing() {
        assertThat(resolver.resolve("/api/v1/payments")).isEqualTo(Optional.empty());
    }

    @Test
    void toleratesATrailingSlashOnTheConfiguredUri() {
        RouteResolver trailingSlash = new RouteResolver(properties(
                new GatewayProperties.Route("orders", "/api/v1/orders", "http://order-service:8080/", false)));

        assertThat(trailingSlash.resolve("/api/v1/orders").orElseThrow().downstreamUri())
                .isEqualTo("http://order-service:8080/api/v1/orders");
    }

    private GatewayProperties properties(GatewayProperties.Route... routes) {
        return new GatewayProperties(List.of(routes),
                new GatewayProperties.RateLimit(true, 60, 20),
                Duration.ofSeconds(2), Duration.ofSeconds(10), "order-platform");
    }
}
