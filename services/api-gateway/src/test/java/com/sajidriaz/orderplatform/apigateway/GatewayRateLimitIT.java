package com.sajidriaz.orderplatform.apigateway;

import com.sajidriaz.orderplatform.apigateway.support.StubDownstreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Rate limiting, with a budget of two requests and an effectively frozen refill so the third
 * request is deterministically refused.
 *
 * <p>A separate class from {@link GatewayRoutingIT} because the budget is a property of the
 * application context: sharing one context would make every other test's request count part of
 * this test's setup, and one added assertion elsewhere could start tripping the limit.
 *
 * <p>Refill behaviour itself is covered exactly, on a controllable clock, in
 * {@code TokenBucketRateLimiterTest} — this test is about the HTTP contract: 429 with
 * {@code Retry-After}, the {@code X-RateLimit-*} headers, and an RFC 9457 body.
 */
@SpringBootTest (
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                       "order-platform.gateway.rate-limit.capacity=2",
                       "order-platform.gateway.rate-limit.refill-per-second=0.01"
        })
class GatewayRateLimitIT
{

    private static final String TOKEN = "token-with-both-order-scopes";

    private static StubDownstreamService orderService;

    private static synchronized StubDownstreamService stub()
    {
        if (orderService == null)
        {
            orderService = StubDownstreamService.start();
        }
        return orderService;
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry)
    {
        registry.add("order-platform.gateway.routes[0].id", () -> "orders");
        registry.add("order-platform.gateway.routes[0].path-prefix", () -> "/api/v1/orders");
        registry.add("order-platform.gateway.routes[0].uri", () -> stub().baseUrl());
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp()
    {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        client = builder.build();
        stub().reset();
        stub().respondWith(200, "{\"status\":\"PENDING\"}");
        given(jwtDecoder.decode(anyString())).willAnswer(invocation ->
        {
            String token = invocation.getArgument(0);
            if (TOKEN.equals(token))
            {
                return jwt(token);
            }
            throw new BadJwtException("Invalid token");
        });
    }

    @Test
    void exceedingTheBudget_returns429WithRetryAfterAndProblemJson()
    {
        assertThat(getOrder().getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(getOrder().getStatus().value()).isEqualTo(HttpStatus.OK.value());

        EntityExchangeResult<JsonNode> refused = getOrder();

        assertThat(refused.getStatus().value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(refused.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isNotNull()
                .satisfies(retryAfter -> assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1L));
        assertThat(refused.getResponseHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(refused.getResponseHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(refused.getResponseHeaders().getContentType()).isNotNull();
        assertThat(refused.getResponseHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(refused.getResponseBody()).isNotNull();
        assertThat(refused.getResponseBody().get("status").asInt()).isEqualTo(429);
        assertThat(refused.getResponseBody().get("correlationId").asString()).isNotBlank();

        // Shedding load means the downstream is spared, not merely that the client is told no.
        assertThat(stub().requestCount()).isEqualTo(2);
    }

    private EntityExchangeResult<JsonNode> getOrder()
    {
        return client.get()
                .uri("/api/v1/orders/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private static Jwt jwt(String tokenValue)
    {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject("3b3a382f-216b-4edf-8118-c93ac7db1a59")
                .issuer("https://test-issuer.local/realms/order-platform")
                .audience(List.of("order-platform"))
                .claim("scope", "orders:read orders:write")
                .issuedAt(Instant.now().minus(5, ChronoUnit.SECONDS))
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
    }
}
