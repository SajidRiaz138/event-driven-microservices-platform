package com.sajidriaz.orderplatform.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Gateway configuration: the route table, the rate-limit budget and the downstream timeouts.
 *
 * <p>Routes are matched by path prefix, longest prefix first. That is all the routing this
 * platform needs — the public API is partitioned by path per owning service — and being
 * configuration rather than code means a new service is a YAML entry.
 *
 * @param routes             route table; see {@link Route}
 * @param rateLimit          per-client request budget
 * @param connectTimeout     how long to wait for a downstream connection
 * @param readTimeout        how long to wait for a downstream response. Longer than the
 *                           services' own work but well short of a client giving up, so a
 *                           stalled downstream surfaces as a 504 rather than a hung request
 * @param audience           audience this gateway requires in the {@code aud} claim
 */
@ConfigurationProperties(prefix = "order-platform.gateway")
public record GatewayProperties(
        @DefaultValue List<Route> routes,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("order-platform") String audience) {

    /**
     * A single route.
     *
     * @param id          name used in logs and metrics
     * @param pathPrefix  request path prefix this route claims, e.g. {@code /api/v1/orders}
     * @param uri         downstream base URI
     * @param stripPrefix whether to strip {@code pathPrefix} before forwarding. Off for the
     *                    service routes, because the services own the same paths the public API
     *                    exposes — rewriting would create two vocabularies for one resource. On
     *                    for the token route, whose downstream path is an identity-provider
     *                    detail the public API should not leak
     */
    public record Route(
            String id,
            String pathPrefix,
            String uri,
            @DefaultValue("false") boolean stripPrefix) {
    }

    /**
     * Per-client token bucket.
     *
     * @param enabled         whether to enforce the budget
     * @param capacity        burst size: requests available at once
     * @param refillPerSecond sustained rate at which the bucket refills
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60") int capacity,
            @DefaultValue("20") double refillPerSecond) {
    }
}
