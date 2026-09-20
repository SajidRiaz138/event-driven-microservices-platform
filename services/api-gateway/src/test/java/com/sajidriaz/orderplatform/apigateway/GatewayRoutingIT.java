package com.sajidriaz.orderplatform.apigateway;

import com.sajidriaz.orderplatform.apigateway.support.StubDownstreamService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * The gateway's edge behaviour: routing, fail-fast token validation, scope checks, correlation-id
 * propagation and the RFC 9457 error contract.
 *
 * <p>Runs against a real embedded server and a real {@link StubDownstreamService}, so a forwarded
 * request is asserted on both sides — what the client got back, and what the service actually
 * received. Token <em>decoding</em> is stubbed here so these cases stay about routing and
 * authorization rules; real RS256 validation against a real Keycloak and the version-controlled
 * realm export is covered by {@link KeycloakRealmImportIT}, and against a real JWKS by
 * order-service's own suite.
 *
 * <p>No Docker required, so this is not gated on {@code DOCKER_AVAILABLE}.
 */
@SpringBootTest (webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT
{

    private static final String FULL_SCOPE_TOKEN = "token-with-both-order-scopes";
    private static final String READ_ONLY_TOKEN = "token-with-read-scope-only";
    private static final String CUSTOMER_SUBJECT = "3b3a382f-216b-4edf-8118-c93ac7db1a59";

    private static StubDownstreamService orderService;

    /** A port nothing listens on, to exercise the unreachable-downstream path. */
    private static final int UNREACHABLE_PORT = 1;

    @BeforeAll
    static void startStub()
    {
        stub();
    }

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
        // The whole route table is declared here: Spring Boot binds a list from the
        // highest-precedence property source that defines it rather than merging sources, so a
        // partial override would silently drop the other entries.
        registry.add("order-platform.gateway.routes[0].id", () -> "orders");
        registry.add("order-platform.gateway.routes[0].path-prefix", () -> "/api/v1/orders");
        registry.add("order-platform.gateway.routes[0].uri", () -> stub().baseUrl());
        registry.add("order-platform.gateway.routes[1].id", () -> "auth-token");
        registry.add("order-platform.gateway.routes[1].path-prefix", () -> "/api/v1/auth/token");
        registry.add("order-platform.gateway.routes[1].uri", () -> stub().baseUrl() + "/token");
        registry.add("order-platform.gateway.routes[1].strip-prefix", () -> "true");
        registry.add("order-platform.gateway.routes[2].id", () -> "unreachable");
        registry.add("order-platform.gateway.routes[2].path-prefix", () -> "/api/v1/unreachable");
        registry.add("order-platform.gateway.routes[2].uri", () -> "http://127.0.0.1:" + UNREACHABLE_PORT);
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
        stub().respondWith(202, "{\"orderId\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");

        given(jwtDecoder.decode(anyString())).willAnswer(invocation ->
        {
            String token = invocation.getArgument(0);
            return switch (token)
            {
                case FULL_SCOPE_TOKEN -> jwt(token, "orders:read orders:write");
                case READ_ONLY_TOKEN -> jwt(token, "orders:read");
                // Anything else is what a real decoder does with a token whose signature,
                // issuer, audience or expiry does not hold up.
                default -> throw new BadJwtException("Invalid token");
            };
        });
    }

    @Test
    void routing_forwardsAnAuthorizedRequestDownstreamWithTheTokenAndCorrelationId()
    {
        String body = """
                { "lines": [ { "sku": "SKU-1001", "quantity": 2 } ], "currency": "USD", "paymentInstrumentId": "pi_1" }
                """;

        EntityExchangeResult<JsonNode> response = client.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE_TOKEN)
                .header("Idempotency-Key", "key-1")
                .body(body)
                .exchange()
                .returnResult(JsonNode.class);

        // The downstream response reaches the client unchanged, headers included.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getResponseHeaders().getFirst("X-Downstream-Marker")).isEqualTo("order-service-stub");
        assertThat(response.getResponseBody()).isNotNull();
        assertThat(response.getResponseBody().get("status").asString()).isEqualTo("PENDING");

        StubDownstreamService.ReceivedRequest received = stub().lastRequest();
        assertThat(received).isNotNull();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.uri()).isEqualTo("/api/v1/orders");
        assertThat(received.body()).contains("SKU-1001");
        // The token is forwarded, not replaced by a trusted header: order-service validates it
        // itself and derives the customer id from `sub` (ADR-0009).
        assertThat(received.header(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + FULL_SCOPE_TOKEN);
        assertThat(received.header("Idempotency-Key")).isEqualTo("key-1");
        assertThat(received.header("X-Correlation-Id")).isNotBlank();
        assertThat(received.header("X-Forwarded-For")).isNotBlank();
        // Advertised so a client can pace itself rather than discovering the limit by refusal.
        assertThat(response.getResponseHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
    }

    @Test
    void routing_generatesACorrelationIdWhenTheClientSendsNone_andAdoptsItWhenItDoes()
    {
        EntityExchangeResult<JsonNode> generated = getOrder(FULL_SCOPE_TOKEN, null);
        String generatedId = generated.getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(generatedId).isNotBlank();
        assertThat(stub().lastRequest().header("X-Correlation-Id")).isEqualTo(generatedId);

        EntityExchangeResult<JsonNode> adopted = getOrder(FULL_SCOPE_TOKEN, "client-supplied-id");
        assertThat(adopted.getResponseHeaders().getFirst("X-Correlation-Id")).isEqualTo("client-supplied-id");
        assertThat(stub().lastRequest().header("X-Correlation-Id")).isEqualTo("client-supplied-id");
    }

    @Test
    void routing_forwardsTheQueryString()
    {
        client.get()
                .uri("/api/v1/orders/" + UUID.randomUUID() + "?expand=lines")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE_TOKEN)
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(stub().lastRequest().uri()).endsWith("?expand=lines");
    }

    @Test
    void edgeAuth_noToken_returns401AndNeverReachesTheService()
    {
        EntityExchangeResult<JsonNode> response = client.get()
                .uri("/api/v1/orders/" + UUID.randomUUID())
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertProblemJson(response, 401);
        // Failing fast is the point: the request must not have cost a downstream round trip.
        assertThat(stub().requestCount()).isZero();
    }

    @Test
    void edgeAuth_invalidToken_returns401AndNeverReachesTheService()
    {
        EntityExchangeResult<JsonNode> response = getOrder("not-a-valid-token", null);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertProblemJson(response, 401);
        assertThat(stub().requestCount()).isZero();
    }

    @Test
    void edgeAuthz_readOnlyToken_cannotPlaceAnOrder_returns403()
    {
        EntityExchangeResult<JsonNode> response = client.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READ_ONLY_TOKEN)
                .header("Idempotency-Key", "key-2")
                .body("{}")
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertProblemJson(response, 403);
        assertThat(stub().requestCount()).isZero();
    }

    @Test
    void tokenRoute_isPublicAndStripsTheOidcPath()
    {
        stub().respondWith(200, "{\"access_token\":\"issued\"}");

        EntityExchangeResult<JsonNode> response = client.post()
                .uri("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password&username=demo-customer&password=demo-password")
                .exchange()
                .returnResult(JsonNode.class);

        // A client cannot present a token in order to obtain one.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(stub().lastRequest().uri()).isEqualTo("/token");
        assertThat(stub().lastRequest().body()).contains("grant_type=password");
    }

    @Test
    void routing_unknownPath_returns404ProblemJson()
    {
        EntityExchangeResult<JsonNode> response = client.get()
                .uri("/api/v1/payments/123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE_TOKEN)
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertProblemJson(response, 404);
    }

    @Test
    void routing_unreachableDownstream_returns502ProblemJson()
    {
        EntityExchangeResult<JsonNode> response = client.get()
                .uri("/api/v1/unreachable/thing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE_TOKEN)
                .exchange()
                .returnResult(JsonNode.class);

        // 502, not 500: the gateway is fine, the service behind it is not — and unlike a
        // timeout, a refused connection means the request was never received, so it is safe
        // to retry.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertProblemJson(response, 502);
    }

    private EntityExchangeResult<JsonNode> getOrder(String token, String correlationId)
    {
        RestTestClient.RequestHeadersSpec<?> request = client.get()
                .uri("/api/v1/orders/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (correlationId != null)
        {
            request = request.header("X-Correlation-Id", correlationId);
        }
        return request.exchange().returnResult(JsonNode.class);
    }

    private void assertProblemJson(EntityExchangeResult<JsonNode> response, int expectedStatus)
    {
        assertThat(response.getResponseHeaders().getContentType()).isNotNull();
        assertThat(response.getResponseHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(response.getResponseBody()).isNotNull();
        assertThat(response.getResponseBody().get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(response.getResponseBody().get("title").asString()).isNotBlank();
        assertThat(response.getResponseBody().get("type").asString()).isNotBlank();
        // Even a refused request stays traceable.
        assertThat(response.getResponseBody().get("correlationId").asString()).isNotBlank();
        assertThat(response.getResponseHeaders().getFirst("X-Correlation-Id")).isNotNull();
    }

    private static Jwt jwt(String tokenValue, String scope)
    {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(CUSTOMER_SUBJECT)
                .issuer("https://test-issuer.local/realms/order-platform")
                .audience(java.util.List.of("order-platform"))
                .claim("scope", scope)
                .issuedAt(Instant.now().minus(5, ChronoUnit.SECONDS))
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
    }
}
