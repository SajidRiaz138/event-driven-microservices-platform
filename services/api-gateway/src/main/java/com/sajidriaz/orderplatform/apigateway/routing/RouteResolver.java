package com.sajidriaz.orderplatform.apigateway.routing;

import com.sajidriaz.orderplatform.apigateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a request path to the downstream URI that should serve it.
 *
 * <p>Longest matching prefix wins, so a more specific route can be added in front of a general
 * one without ordering the YAML carefully — {@code /api/v1/orders/summary} can be split out from
 * {@code /api/v1/orders} later without touching either entry. Matching is on whole path
 * segments: the prefix {@code /api/v1/orders} claims {@code /api/v1/orders} and
 * {@code /api/v1/orders/{id}} but not {@code /api/v1/ordersearch}, which a plain
 * {@code startsWith} would hand to the wrong service.
 */
@Component
public class RouteResolver {

    private final List<GatewayProperties.Route> routesByPrefixLengthDesc;

    public RouteResolver(GatewayProperties properties) {
        this.routesByPrefixLengthDesc = properties.routes().stream()
                .sorted(Comparator.comparingInt((GatewayProperties.Route route) ->
                        route.pathPrefix().length()).reversed())
                .toList();
    }

    public Optional<ResolvedRoute> resolve(String requestPath) {
        return routesByPrefixLengthDesc.stream()
                .filter(route -> matches(route.pathPrefix(), requestPath))
                .findFirst()
                .map(route -> new ResolvedRoute(route, downstreamUri(route, requestPath)));
    }

    private boolean matches(String prefix, String path) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    private String downstreamUri(GatewayProperties.Route route, String requestPath) {
        String remainder = route.stripPrefix()
                ? requestPath.substring(route.pathPrefix().length())
                : requestPath;
        String base = route.uri().endsWith("/")
                ? route.uri().substring(0, route.uri().length() - 1)
                : route.uri();
        return base + remainder;
    }

    /**
     * @param route         the matched route
     * @param downstreamUri absolute URI to forward to, query string excluded
     */
    public record ResolvedRoute(GatewayProperties.Route route, String downstreamUri) {
    }
}
