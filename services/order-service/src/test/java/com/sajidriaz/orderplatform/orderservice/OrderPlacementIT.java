package com.sajidriaz.orderplatform.orderservice;

import com.sajidriaz.orderplatform.common.Money;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReleased;
import com.sajidriaz.orderplatform.events.inventory.StockReserved;
import com.sajidriaz.orderplatform.events.payment.PaymentAuthorized;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptured;
import com.sajidriaz.orderplatform.events.payment.PaymentDeclined;
import com.sajidriaz.orderplatform.orderservice.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.orderservice.messaging.Topics;
import com.sajidriaz.orderplatform.orderservice.support.TestJwtIssuer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end saga integration tests (Testcontainers Postgres + Kafka). Covers
 * REQUIREMENTS.md scenarios S-1 (happy path, via outbox+relay), S-3 (payment-declined
 * compensation), S-5/S-18 (idempotency), and S-15 (cross-customer ownership), plus the
 * compensation loop closing on {@code StockReleased} and the RFC 9457 client-error paths.
 *
 * <p>payment-service and inventory-service do not exist yet (Phase 1) — this test plays
 * their part by publishing the reply events (StockReserved, PaymentAuthorized,
 * PaymentCaptured, PaymentDeclined, StockReleased) directly to their topics, exactly as
 * those services would.
 *
 * <p>HTTP goes through Spring's own {@link RestTestClient} rather than RestAssured, whose
 * bundled legacy Groovy HTTP client fails at transport level in this environment with a
 * NullPointerException inside {@code HTTPBuilder.doRequest} — every HTTP-touching test
 * here used to error for reasons that had nothing to do with the application.
 * {@code RestTestClient} is Spring Framework's servlet-side test client (Boot 4 moved
 * {@code TestRestTemplate} into a separate {@code spring-boot-restclient-test} module that
 * is not on this build's classpath). Like {@code TestRestTemplate} it never throws on a
 * 4xx, so every status code below is asserted explicitly rather than inferred from the
 * absence of an exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class OrderPlacementIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("orderdb")
            .withUsername("appuser")
            .withPassword("testpass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.1.2"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // This service validates the access token itself (ADR-0009): it fetches this JWKS,
        // picks the key by `kid` and verifies the RS256 signature, then checks issuer,
        // audience and expiry. Only the signing authority is local to the test.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtIssuer::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        // Fast-ish outbox relay polling so the suite doesn't wait long for events to
        // reach Kafka, while staying close to the production default (500ms) rather
        // than hammering the DB pool with an unrealistically tight interval.
        registry.add("order-platform.outbox.relay.fixed-delay-ms", () -> "300");
        // Step deadlines far beyond this suite's runtime. Several scenarios deliberately
        // leave an order awaiting a reply that never comes (the ownership and idempotency
        // cases), and the timeout sweeper would eventually — and correctly — cancel those.
        // Pushing the deadlines out keeps the sweeper from racing assertions here; its
        // behavior is covered deterministically in SagaOrchestratorTest instead.
        registry.add("order-platform.saga.step-timeout.stock-reservation-seconds", () -> "600");
        registry.add("order-platform.saga.step-timeout.payment-authorization-seconds", () -> "600");
        registry.add("order-platform.saga.step-timeout.payment-capture-seconds", () -> "600");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    private static KafkaProducer<String, byte[]> producer;
    private static KafkaConsumer<String, byte[]> verifierConsumer;
    private static final List<Envelope> bufferedEnvelopes = new ArrayList<>();
    private final EnvelopeCodec envelopeCodec = new EnvelopeCodec();

    @BeforeEach
    void setUpHttpClient() {
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        client = builder.build();
    }

    @BeforeAll
    static void setUpKafkaClients() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer = new KafkaProducer<>(producerProps);

        // One shared consumer, subscribed to every topic this suite verifies against,
        // for the whole test class — avoids paying a fresh consumer-group-join per
        // assertion (each join taking real wall-clock time against a fresh broker).
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-verifier");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        verifierConsumer = new KafkaConsumer<>(consumerProps);
        verifierConsumer.subscribe(List.of(
                Topics.EVENTS_ORDER_CREATED,
                Topics.EVENTS_ORDER_CONFIRMED,
                Topics.EVENTS_ORDER_CANCELLED,
                Topics.COMMANDS_INVENTORY_RELEASE));
    }

    @AfterAll
    static void tearDownKafkaClients() {
        if (producer != null) {
            producer.close();
        }
        if (verifierConsumer != null) {
            verifierConsumer.close();
        }
    }

    @Test
    void placeOrder_returns202WithLocationAndCorrelationId_andEmitsOrderCreated() {
        String customerId = "cust-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        String body = """
                {
                  "lines": [ { "sku": "SKU-1001", "quantity": 2 } ],
                  "currency": "USD",
                  "paymentInstrumentId": "pi_test_1"
                }
                """;

        EntityExchangeResult<JsonNode> created = postOrder(customerId, idempotencyKey, body);

        assertThat(created.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(created.getResponseHeaders().getLocation()).isNotNull();
        assertThat(created.getResponseHeaders().getFirst("X-Correlation-Id")).isNotNull();
        assertThat(created.getResponseBody()).isNotNull();
        assertThat(created.getResponseBody().get("status").asString()).isEqualTo("PENDING");

        String orderId = created.getResponseBody().get("orderId").asString();
        assertThat(created.getResponseHeaders().getLocation().toString())
                .endsWith("/api/v1/orders/" + orderId);

        // Read-your-writes: the order is committed before the 202 is returned.
        EntityExchangeResult<JsonNode> fetched = getOrder(customerId, orderId);
        assertThat(fetched.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(fetched.getResponseBody()).isNotNull();
        assertThat(fetched.getResponseBody().get("status").asString()).isEqualTo("PENDING");

        // The outbox relay must publish OrderCreated onto Kafka within a few seconds.
        assertEventPublished(orderId, "events.order.created");
    }

    /**
     * ADR-0013: "one correlationId stitches together the logs of an entire business flow
     * across services". It did not. The saga minted its own {@code UUID.randomUUID()}, so
     * the id handed back to the caller in {@code X-Correlation-Id} appeared on no Envelope
     * and in no other service's logs, and the id that did travel appeared in no response.
     * Grepping for the value the demo prints found nothing outside the gateway.
     *
     * <p>The id on the wire must be the id the caller was given.
     */
    @Test
    void placeOrder_putsTheRequestCorrelationIdOnTheEnvelope() {
        String customerId = "cust-" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        String body = """
                { "lines": [ { "sku": "SKU-1001", "quantity": 1 } ], "currency": "USD", "paymentInstrumentId": "pi_corr" }
                """;

        EntityExchangeResult<JsonNode> created = client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(customerId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Correlation-Id", correlationId)
                .body(body)
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(created.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(created.getResponseHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
        assertThat(created.getResponseBody()).isNotNull();

        String orderId = created.getResponseBody().get("orderId").asString();

        Envelope published = awaitEvent(orderId, "events.order.created");
        assertThat(String.valueOf(published.getCorrelationId())).isEqualTo(correlationId);
    }

    @Test
    void happyPath_stockReservedAuthorizedCaptured_confirmsOrder() {
        String customerId = "cust-" + UUID.randomUUID();
        String orderId = placeOrderAndGetId(customerId, "SKU-1001", 1);
        UUID orderUuid = UUID.fromString(orderId);

        publishStockReserved(orderUuid);
        publishPaymentAuthorized(orderUuid);
        publishPaymentCaptured(orderUuid);

        awaitOrderStatus(customerId, orderId, "CONFIRMED");
        assertEventPublished(orderId, "events.order.confirmed");
    }

    @Test
    void compensation_stockReservedThenPaymentDeclined_releasesStockAndCancels() {
        String customerId = "cust-" + UUID.randomUUID();
        String orderId = placeOrderAndGetId(customerId, "SKU-1002", 1);
        UUID orderUuid = UUID.fromString(orderId);

        publishStockReserved(orderUuid);
        publishPaymentDeclined(orderUuid);

        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            EntityExchangeResult<JsonNode> response = getOrder(customerId, orderId);
            assertThat(response.getStatus().value()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getResponseBody()).isNotNull();
            assertThat(response.getResponseBody().get("status").asString()).isEqualTo("CANCELLED");
            assertThat(response.getResponseBody().get("reason").asString()).isEqualTo("PAYMENT_DECLINED");
        });

        assertEventPublished(orderId, "commands.inventory.release");
        assertEventPublished(orderId, "events.order.cancelled");
    }

    /**
     * The compensation loop must close: {@code events.inventory.released.v1} was declared
     * but never consumed, so every compensated saga sat at step
     * {@code AWAITING_STOCK_RELEASE} forever even once inventory had released the stock.
     */
    @Test
    void compensationCompletes_whenStockReleasedArrives_sagaStepReachesDone() {
        String customerId = "cust-" + UUID.randomUUID();
        String orderId = placeOrderAndGetId(customerId, "SKU-1002", 1);
        UUID orderUuid = UUID.fromString(orderId);

        publishStockReserved(orderUuid);
        publishPaymentDeclined(orderUuid);

        // Wait for the cancellation to land before confirming the release, otherwise the
        // confirmation could arrive before the saga is even awaiting it.
        awaitOrderStatus(customerId, orderId, "CANCELLED");
        assertThat(currentStepOf(orderUuid)).isEqualTo("AWAITING_STOCK_RELEASE");

        publishStockReleased(orderUuid);

        Awaitility.await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(currentStepOf(orderUuid)).isEqualTo("DONE"));

        // The order stays CANCELLED: closing the compensation bookkeeping must never
        // change the customer-visible outcome.
        EntityExchangeResult<JsonNode> response = getOrder(customerId, orderId);
        assertThat(response.getResponseBody()).isNotNull();
        assertThat(response.getResponseBody().get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void idempotency_sameKeyTwice_oneOrder_thenSecondReplaysOriginal_andConflictOnDifferentBody() {
        String customerId = "cust-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                { "lines": [ { "sku": "SKU-1001", "quantity": 1 } ], "currency": "USD", "paymentInstrumentId": "pi_a" }
                """;

        EntityExchangeResult<JsonNode> first = postOrder(customerId, idempotencyKey, body);
        assertThat(first.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(first.getResponseBody()).isNotNull();
        String firstOrderId = first.getResponseBody().get("orderId").asString();

        // Same key + same body -> replays the original response (S-5).
        EntityExchangeResult<JsonNode> replay = postOrder(customerId, idempotencyKey, body);
        assertThat(replay.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(replay.getResponseBody()).isNotNull();
        assertThat(replay.getResponseBody().get("orderId").asString()).isEqualTo(firstOrderId);

        // Same key + different body -> 409 Conflict (S-18), no second order.
        String differentBody = """
                { "lines": [ { "sku": "SKU-1002", "quantity": 3 } ], "currency": "USD", "paymentInstrumentId": "pi_a" }
                """;
        EntityExchangeResult<JsonNode> conflict = postOrder(customerId, idempotencyKey, differentBody);
        assertThat(conflict.getStatus().value()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void ownership_crossCustomerGet_returns404() {
        String ownerCustomerId = "cust-" + UUID.randomUUID();
        String orderId = placeOrderAndGetId(ownerCustomerId, "SKU-1001", 1);

        String otherCustomerId = "cust-" + UUID.randomUUID();
        EntityExchangeResult<JsonNode> response = getOrder(otherCustomerId, orderId);

        // 404, never 403: existence must not be revealed (S-15).
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    /**
     * Client errors must be reported as 400 with an RFC 9457 body. Both of these
     * previously fell through to the catch-all handler and were returned as 500.
     */
    @Test
    void clientErrors_missingIdempotencyKeyOrMalformedBody_return400ProblemJson() {
        String customerId = "cust-" + UUID.randomUUID();
        String validBody = """
                { "lines": [ { "sku": "SKU-1001", "quantity": 1 } ], "currency": "USD", "paymentInstrumentId": "pi_a" }
                """;

        EntityExchangeResult<JsonNode> missingHeader = client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(customerId))
                .body(validBody)
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(missingHeader.getStatus().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(missingHeader.getResponseHeaders().getContentType()).isNotNull();
        assertThat(missingHeader.getResponseHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(missingHeader.getResponseBody()).isNotNull();
        // RFC 9457 members plus the platform's required correlationId extension.
        assertThat(missingHeader.getResponseBody().get("status").asInt()).isEqualTo(400);
        assertThat(missingHeader.getResponseBody().get("title").asString()).isNotBlank();
        assertThat(missingHeader.getResponseBody().get("type").asString()).isNotBlank();
        assertThat(missingHeader.getResponseBody().get("instance").asString()).isEqualTo("/api/v1/orders");
        assertThat(missingHeader.getResponseBody().get("correlationId").asString()).isNotBlank();
        assertThat(missingHeader.getResponseHeaders().getFirst("X-Correlation-Id")).isNotNull();

        EntityExchangeResult<JsonNode> malformed =
                postOrder(customerId, UUID.randomUUID().toString(), "{ not json");
        assertThat(malformed.getStatus().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(malformed.getResponseBody()).isNotNull();
        assertThat(malformed.getResponseBody().get("status").asInt()).isEqualTo(400);
    }

    // ---------------------------------------------------------------------
    // Authentication and scope authorization (ADR-0009)
    //
    // These cover what replacing the X-User-Id stand-in actually bought: identity now comes
    // from a token this service validated, so a token that is absent, expired, signed by
    // somebody else, or minted for another audience must be refused — and a valid token still
    // only permits the operations its scopes cover.
    // ---------------------------------------------------------------------

    @Test
    void auth_missingToken_returns401ProblemJson() {
        EntityExchangeResult<JsonNode> response = client.get().uri("/api/v1/orders/" + UUID.randomUUID())
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertProblemJson(response, 401);
    }

    @Test
    void auth_expiredToken_returns401() {
        assertRejectedWith401(TestJwtIssuer.expiredTokenFor("cust-" + UUID.randomUUID()));
    }

    @Test
    void auth_tokenSignedByAKeyOutsideTheJwks_returns401() {
        assertRejectedWith401(TestJwtIssuer.tokenSignedByUntrustedKey("cust-" + UUID.randomUUID()));
    }

    @Test
    void auth_tokenMintedForAnotherAudience_returns401() {
        assertRejectedWith401(TestJwtIssuer.tokenForForeignAudience("cust-" + UUID.randomUUID()));
    }

    @Test
    void authz_readOnlyToken_cannotPlaceOrder_returns403ProblemJson() {
        String customerId = "cust-" + UUID.randomUUID();
        String body = """
                { "lines": [ { "sku": "SKU-1001", "quantity": 1 } ], "currency": "USD", "paymentInstrumentId": "pi_a" }
                """;

        EntityExchangeResult<JsonNode> response = client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.tokenWithScopes(customerId, "orders:read"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body)
                .exchange()
                .returnResult(JsonNode.class);

        // 403, not 404: the caller is known, the operation simply is not covered by the token.
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertProblemJson(response, 403);
    }

    @Test
    void authz_writeOnlyToken_cannotReadOrder_returns403() {
        String customerId = "cust-" + UUID.randomUUID();
        String orderId = placeOrderAndGetId(customerId, "SKU-1001", 1);

        EntityExchangeResult<JsonNode> response = client.get().uri("/api/v1/orders/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.tokenWithScopes(customerId, "orders:write"))
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    private void assertRejectedWith401(String token) {
        EntityExchangeResult<JsonNode> response = client.get().uri("/api/v1/orders/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .returnResult(JsonNode.class);

        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertProblemJson(response, 401);
    }

    /**
     * Rejections from the security filter chain must use the same error contract as the rest of
     * the API (RFC 9457 + the correlationId extension), not Spring Security's default empty
     * body — including the correlation id, which is what makes a refused call traceable.
     */
    private void assertProblemJson(EntityExchangeResult<JsonNode> response, int expectedStatus) {
        assertThat(response.getResponseHeaders().getContentType()).isNotNull();
        assertThat(response.getResponseHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(response.getResponseBody()).isNotNull();
        assertThat(response.getResponseBody().get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(response.getResponseBody().get("title").asString()).isNotBlank();
        assertThat(response.getResponseBody().get("type").asString()).isNotBlank();
        assertThat(response.getResponseBody().get("correlationId").asString()).isNotBlank();
        assertThat(response.getResponseHeaders().getFirst("X-Correlation-Id")).isNotNull();
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    private EntityExchangeResult<JsonNode> postOrder(String customerId, String idempotencyKey, String body) {
        return client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(customerId))
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> getOrder(String customerId, String orderId) {
        return client.get().uri("/api/v1/orders/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(customerId))
                .exchange()
                .returnResult(JsonNode.class);
    }

    /**
     * The caller's identity is the token's {@code sub} claim — the customer id is no longer a
     * header the client chooses (ADR-0009). Each distinct {@code customerId} in these tests
     * therefore becomes a distinct subject, which is exactly what the cross-customer ownership
     * case needs.
     */
    private String bearerFor(String customerId) {
        return "Bearer " + TestJwtIssuer.tokenFor(customerId);
    }

    private String placeOrderAndGetId(String customerId, String sku, int quantity) {
        String body = """
                { "lines": [ { "sku": "%s", "quantity": %d } ], "currency": "USD", "paymentInstrumentId": "pi_test" }
                """.formatted(sku, quantity);
        EntityExchangeResult<JsonNode> response = postOrder(customerId, UUID.randomUUID().toString(), body);
        assertThat(response.getStatus().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getResponseBody()).isNotNull();
        return response.getResponseBody().get("orderId").asString();
    }

    private void awaitOrderStatus(String customerId, String orderId, String expectedStatus) {
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            EntityExchangeResult<JsonNode> response = getOrder(customerId, orderId);
            assertThat(response.getStatus().value()).isEqualTo(HttpStatus.OK.value());
            assertThat(response.getResponseBody()).isNotNull();
            assertThat(response.getResponseBody().get("status").asString()).isEqualTo(expectedStatus);
        });
    }

    private String currentStepOf(UUID orderId) {
        return jdbc.queryForObject("select current_step from saga_instance where order_id = ?", String.class, orderId);
    }

    // ---------------------------------------------------------------------
    // Kafka helpers — standing in for inventory-service / payment-service
    // ---------------------------------------------------------------------

    private void publishStockReserved(UUID orderId) {
        StockReserved payload = StockReserved.newBuilder()
                .setOrderId(orderId)
                .setReservationId(UUID.randomUUID())
                .setReservedAt(Instant.now())
                .setExpiresAt(Instant.now().plusSeconds(900))
                .build();
        publish(Topics.EVENTS_INVENTORY_RESERVED, orderId, "events.inventory.reserved", payload);
    }

    private void publishStockReleased(UUID orderId) {
        StockReleased payload = StockReleased.newBuilder()
                .setOrderId(orderId)
                .setReservationId(UUID.randomUUID())
                .setReleasedAt(Instant.now())
                .build();
        publish(Topics.EVENTS_INVENTORY_RELEASED, orderId, "events.inventory.released", payload);
    }

    private void publishPaymentAuthorized(UUID orderId) {
        PaymentAuthorized payload = PaymentAuthorized.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentAttemptId(UUID.randomUUID())
                .setPaymentOperationId(UUID.randomUUID())
                .setAmount(Money.newBuilder().setMinorUnits(1999).setCurrency("USD").build())
                .setAuthorizedAt(Instant.now())
                .build();
        publish(Topics.EVENTS_PAYMENT_AUTHORIZED, orderId, "events.payment.authorized", payload);
    }

    private void publishPaymentCaptured(UUID orderId) {
        PaymentCaptured payload = PaymentCaptured.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentAttemptId(UUID.randomUUID())
                .setPaymentOperationId(UUID.randomUUID())
                .setProviderReference("prov-ref-1")
                .setAmount(Money.newBuilder().setMinorUnits(1999).setCurrency("USD").build())
                .setCapturedAt(Instant.now())
                .build();
        publish(Topics.EVENTS_PAYMENT_CAPTURED, orderId, "events.payment.captured", payload);
    }

    private void publishPaymentDeclined(UUID orderId) {
        PaymentDeclined payload = PaymentDeclined.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentAttemptId(UUID.randomUUID())
                .setDeclinedAt(Instant.now())
                .build();
        publish(Topics.EVENTS_PAYMENT_DECLINED, orderId, "events.payment.declined", payload);
    }

    private void publish(String topic, UUID orderId, String type, org.apache.avro.specific.SpecificRecordBase payload) {
        byte[] envelopeBytes = envelopeCodec.encodeEnvelope(MessageKind.EVENT, type, UUID.randomUUID(), null, orderId, payload);
        producer.send(new ProducerRecord<>(topic, orderId.toString(), envelopeBytes));
        producer.flush();
    }

    /**
     * Polls the shared verifier consumer (subscribed once, in {@code @BeforeAll}, to
     * every topic this suite checks) for an envelope of {@code expectedType}
     * concerning {@code orderId}. Records that don't match are buffered (not
     * discarded) so a later assertion in the same test class can still find them
     * without missing any poll — the consumer is never re-subscribed mid-suite.
     */
    private void assertEventPublished(String orderId, String expectedType) {
        awaitEvent(orderId, expectedType);
    }

    /**
     * As {@link #assertEventPublished}, but hands the message back so a test can assert on
     * the envelope itself and not merely on its existence.
     */
    private Envelope awaitEvent(String orderId, String expectedType) {
        Iterator<Envelope> bufferedIt = bufferedEnvelopes.iterator();
        while (bufferedIt.hasNext()) {
            Envelope envelope = bufferedIt.next();
            if (envelope.getAggregateId().equals(orderId) && envelope.getType().equals(expectedType)) {
                bufferedIt.remove();
                return envelope;
            }
        }

        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            var records = verifierConsumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, byte[]> record : records) {
                Envelope envelope = envelopeCodec.decodeEnvelope(record.value());
                if (envelope.getAggregateId().equals(orderId) && envelope.getType().equals(expectedType)) {
                    return envelope;
                }
                bufferedEnvelopes.add(envelope);
            }
        }
        throw new AssertionError("Expected message of type " + expectedType + " for order " + orderId
                + " but none arrived within timeout.");
    }
}
