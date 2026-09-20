package com.sajidriaz.orderplatform.apigateway;

import com.sajidriaz.orderplatform.apigateway.support.StubDownstreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the auth half of this platform end to end against a <b>real Keycloak</b> running the
 * version-controlled realm export: the realm imports, it issues an RS256 token with the audience
 * and scopes the services expect, and the gateway accepts that token after verifying it against
 * Keycloak's own JWKS.
 *
 * <p>Without this test the realm export would be a JSON file nobody executes — the kind of
 * artifact that is subtly wrong (a missing audience mapper, a scope that never reaches the
 * {@code scope} claim) until someone tries it by hand. Everything asserted here comes from the
 * same file {@code deploy/local/compose.yaml} mounts, so {@code make up} and this test cannot
 * drift apart.
 *
 * <p>Plain {@link GenericContainer} from the pinned Testcontainers 2.0.5 rather than a
 * third-party Keycloak module: a realm-import mount, a command and an HTTP wait strategy is all
 * this needs, against an API the build already depends on.
 *
 * <p>Requires Docker, so it is gated like every other container-backed test here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class KeycloakRealmImportIT {

    private static final String REALM = "order-platform";
    private static final String CLIENT_ID = "order-platform-web";
    private static final String USERNAME = "demo-customer";
    private static final String PASSWORD = "demo-password";

    /**
     * The realm export as committed. Resolved relative to this module, since auth-service is
     * configuration rather than a Maven module and has no classpath to load it from.
     */
    private static final Path REALM_EXPORT =
            Path.of("..", "auth-service", "realm", "order-platform-realm.json").toAbsolutePath().normalize();

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer keycloak = new GenericContainer(DockerImageName.parse("quay.io/keycloak/keycloak:26.4"))
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_EXPORT),
                    "/opt/keycloak/data/import/order-platform-realm.json")
            .withCommand("start-dev", "--import-realm")
            // Readiness is the realm's own discovery document, not merely a listening port: the
            // import runs during startup, so a port check would let a test race it.
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static StubDownstreamService orderService;

    private static synchronized StubDownstreamService stub() {
        if (orderService == null) {
            orderService = StubDownstreamService.start();
        }
        return orderService;
    }

    private static String issuerUri() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/" + REALM;
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        // The gateway trusts this issuer and fetches its keys from it — no test double anywhere
        // in the validation path.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakRealmImportIT::issuerUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> issuerUri() + "/protocol/openid-connect/certs");
        registry.add("order-platform.gateway.routes[0].id", () -> "orders");
        registry.add("order-platform.gateway.routes[0].path-prefix", () -> "/api/v1/orders");
        registry.add("order-platform.gateway.routes[0].uri", () -> stub().baseUrl());
        registry.add("order-platform.gateway.routes[1].id", () -> "auth-token");
        registry.add("order-platform.gateway.routes[1].path-prefix", () -> "/api/v1/auth/token");
        registry.add("order-platform.gateway.routes[1].uri",
                () -> issuerUri() + "/protocol/openid-connect/token");
        registry.add("order-platform.gateway.routes[1].strip-prefix", () -> "true");
    }

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        client = builder.build();
        stub().reset();
        stub().respondWith(200, "{\"status\":\"PENDING\"}");
    }

    @Test
    void theRealmExportIsTheFileComposeMounts() throws Exception {
        // Guards the one thing this test cannot assert by behaviour: that it is exercising the
        // committed realm, at the path compose refers to.
        assertThat(Files.exists(REALM_EXPORT)).as("realm export at %s", REALM_EXPORT).isTrue();
        assertThat(Files.readString(REALM_EXPORT))
                .contains("\"realm\": \"order-platform\"")
                .contains("orders:read")
                .contains("orders:write")
                .contains("orders:write:any");
    }

    @Test
    void keycloakIssuesAnRs256TokenWithThePlatformAudienceAndOrderScopes() {
        String accessToken = accessToken(null);

        assertThat(jwtSegment(accessToken, 0))
                .as("JOSE header")
                .contains("\"alg\":\"RS256\"");
        String claims = jwtSegment(accessToken, 1);
        assertThat(claims)
                .contains("\"iss\":\"" + issuerUri() + "\"")
                // The audience mapper in the realm export is what puts this here; without it
                // every service's audience check would reject every real token.
                .contains("order-platform")
                .contains("orders:read")
                .contains("orders:write");
        // `sub` is the customer id order-service persists — a Keycloak user UUID.
        assertThat(claims).contains("\"sub\":\"");
    }

    @Test
    void theGatewayAcceptsARealKeycloakTokenAndForwardsIt() {
        String accessToken = accessToken(null);

        EntityExchangeResult<JsonNode> response = client.get().uri("/api/v1/orders/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange()
                .returnResult(JsonNode.class);

        // Accepted means the full chain held: RS256 signature checked against Keycloak's JWKS by
        // `kid`, plus issuer, audience and expiry (PlatformJwtDecoders), plus the orders:read
        // scope check at the edge.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(stub().lastRequest()).isNotNull();
        assertThat(stub().lastRequest().header(HttpHeaders.AUTHORIZATION))
                .as("the token itself is forwarded, so order-service can validate it too")
                .isEqualTo("Bearer " + accessToken);
    }

    @Test
    void theGatewayRejectsATokenThisRealmDidNotIssue() {
        EntityExchangeResult<JsonNode> response = client.get().uri("/api/v1/orders/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real-token")
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(stub().requestCount()).isZero();
    }

    @Test
    void theTokenRouteThroughTheGatewayIssuesAToken() {
        EntityExchangeResult<JsonNode> response = client.post().uri("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password&client_id=" + CLIENT_ID
                        + "&username=" + USERNAME + "&password=" + PASSWORD)
                .exchange()
                .returnResult(JsonNode.class);

        // One origin for clients: the identity provider's own URL is not part of the public API.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getResponseBody()).isNotNull();
        assertThat(response.getResponseBody().get("access_token").asString()).isNotBlank();
    }

    @Test
    void theAdministrativeScopeIsGrantedOnlyWhenAskedFor() {
        // orders:write:any is an optional client scope in the realm export, so an ordinary
        // session never silently carries the ability to order on somebody else's behalf.
        assertThat(jwtSegment(accessToken(null), 1)).doesNotContain("orders:write:any");
        assertThat(jwtSegment(accessToken("orders:write:any"), 1)).contains("orders:write:any");
    }

    private String accessToken(String requestedScope) {
        String form = "grant_type=password&client_id=" + CLIENT_ID
                + "&username=" + USERNAME + "&password=" + PASSWORD
                + (requestedScope == null ? "" : "&scope=" + requestedScope);
        Map<?, ?> tokenResponse = RestClient.create()
                .post()
                .uri(issuerUri() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        assertThat(tokenResponse).isNotNull();
        return (String) tokenResponse.get("access_token");
    }

    private String jwtSegment(String jwt, int index) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }
}
