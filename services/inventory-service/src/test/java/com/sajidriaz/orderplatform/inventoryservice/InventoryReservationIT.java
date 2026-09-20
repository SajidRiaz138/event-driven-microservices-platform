package com.sajidriaz.orderplatform.inventoryservice;

import com.sajidriaz.orderplatform.commands.inventory.ReleaseStock;
import com.sajidriaz.orderplatform.commands.inventory.ReserveLine;
import com.sajidriaz.orderplatform.commands.inventory.ReserveStock;
import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReservationFailed;
import com.sajidriaz.orderplatform.events.inventory.StockReserved;
import com.sajidriaz.orderplatform.events.order.OrderConfirmed;
import com.sajidriaz.orderplatform.inventoryservice.service.InventoryService;
import org.apache.avro.specific.SpecificRecordBase;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inventory-service against real Postgres and Kafka: the saga participant side of the contract.
 *
 * <p>Plays the orchestrator's part by publishing {@code ReserveStock}/{@code ReleaseStock} commands
 * onto their topics exactly as order-service does — same envelope, same encoder, same partition
 * key — and asserts both the emitted reply events and the resulting database state. Covers
 * scenarios S-1 (reserve), S-2 (insufficient stock), S-3's compensation leg (release), S-6
 * (concurrent last unit, against the real database), and S-10 (redelivery).
 *
 * <p>HTTP goes through Spring's {@link RestTestClient}: RestAssured's bundled legacy Groovy client
 * fails at transport level in this environment, and Boot 4 moved {@code TestRestTemplate} into a
 * module that is not on this classpath. Like {@code TestRestTemplate} it does not throw on 4xx, so
 * status codes are asserted explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class InventoryReservationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("orderdb")
            .withUsername("inventory_svc")
            .withPassword("testpass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Tighten the outbox relay so reply events reach Kafka quickly, while staying close to the
        // production default (500ms) rather than hammering the connection pool.
        registry.add("order-platform.outbox.relay.fixed-delay-ms", () -> "300");
        // Push the TTL sweeper's reach out: several tests deliberately leave a reservation active,
        // and the sweeper would correctly — but inconveniently — expire it mid-assertion. Its
        // behaviour is covered deterministically in the unit tests.
        registry.add("inventory.reservation.default-ttl-seconds", () -> "3600");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryService inventoryService;

    private static KafkaProducer<String, byte[]> producer;
    private static KafkaConsumer<String, byte[]> verifier;
    private static final List<Envelope> buffered = new ArrayList<>();
    private final EnvelopeCodec codec = new EnvelopeCodec();

    @BeforeAll
    static void startKafkaClients() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer = new KafkaProducer<>(producerProps);

        // One consumer for the whole class, subscribed once: a fresh consumer-group join per
        // assertion costs real wall-clock time against a freshly started broker.
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-it-verifier");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        verifier = new KafkaConsumer<>(consumerProps);
        verifier.subscribe(List.of(
                PlatformTopics.EVENTS_INVENTORY_RESERVED,
                PlatformTopics.EVENTS_INVENTORY_RESERVATION_FAILED,
                PlatformTopics.EVENTS_INVENTORY_RELEASED));
    }

    @AfterAll
    static void stopKafkaClients() {
        if (producer != null) {
            producer.close();
        }
        if (verifier != null) {
            verifier.close();
        }
    }

    @Test
    void reserveStockCommand_reservesStockAndEmitsStockReserved() {
        String sku = givenStock(10);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        publishReserveStock(orderId, reservationId, sku, 3, 600);

        Envelope reply = awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RESERVED);
        StockReserved payload = codec.decodePayload(reply, StockReserved.class);
        assertThat(payload.getOrderId()).isEqualTo(orderId);
        assertThat(payload.getReservationId()).isEqualTo(reservationId);
        assertThat(payload.getExpiresAt()).isAfter(payload.getReservedAt());
        // The orchestrator correlates a reply solely by the envelope's aggregateId, so this is the
        // field that must be the order id — getting it wrong strands the saga.
        assertThat(reply.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(reply.getMessageKind()).isEqualTo(MessageKind.EVENT);

        assertThat(reservedOf(sku)).isEqualTo(3);
        assertThat(availableOf(sku)).isEqualTo(7);
        assertThat(statusOfReservation(reservationId)).isEqualTo("ACTIVE");
    }

    @Test
    void reserveStockCommand_withInsufficientStock_emitsStockReservationFailedAndChangesNothing() {
        String sku = givenStock(1);
        UUID orderId = UUID.randomUUID();

        publishReserveStock(orderId, UUID.randomUUID(), sku, 5, 600);

        Envelope reply = awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RESERVATION_FAILED);
        StockReservationFailed payload = codec.decodePayload(reply, StockReservationFailed.class);
        assertThat(payload.getFailedSku()).isEqualTo(sku);

        assertThat(reservedOf(sku)).isZero();
        assertThat(availableOf(sku)).isEqualTo(1);
        // Insufficient stock is a normal business outcome, so the command must not be treated as
        // poison: nothing is left in the DLQ path and the reservation simply does not exist.
        assertThat(reservationCountFor(orderId)).isZero();
    }

    @Test
    void releaseStockCommand_returnsStockAndEmitsStockReleased() {
        String sku = givenStock(5);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        publishReserveStock(orderId, reservationId, sku, 2, 600);
        awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RESERVED);
        assertThat(reservedOf(sku)).isEqualTo(2);

        // The orchestrator sends a reservationId it minted fresh, which matches nothing here; the
        // release must still find the order's active reservation.
        publishReleaseStock(orderId, UUID.randomUUID());

        awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RELEASED);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(reservedOf(sku)).isZero());
        assertThat(availableOf(sku)).isEqualTo(5);
        assertThat(statusOfReservation(reservationId)).isEqualTo("RELEASED");
    }

    @Test
    void redeliveredReserveStockCommand_reservesStockOnlyOnce() {
        String sku = givenStock(10);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        // The same bytes twice: identical messageId, which is exactly what a consumer crash before
        // the offset commit produces on restart (S-10).
        byte[] command = encodeReserveStock(orderId, reservationId, sku, 4, 600);
        publishRaw(PlatformTopics.COMMANDS_INVENTORY_RESERVE, orderId, command);
        publishRaw(PlatformTopics.COMMANDS_INVENTORY_RESERVE, orderId, command);

        awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RESERVED);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(reservedOf(sku)).isEqualTo(4));

        // Give the second delivery time to be (not) applied, then confirm the dedup held.
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(reservedOf(sku)).isEqualTo(4));
        assertThat(reservationCountFor(orderId)).isEqualTo(1);
        assertThat(processedMessageCount()).isPositive();
    }

    @Test
    void orderConfirmedEvent_commitsTheReservationSoStockLeavesOnHand() {
        String sku = givenStock(6);
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        publishReserveStock(orderId, reservationId, sku, 2, 600);
        awaitEvent(orderId, MessageTypes.EVENT_INVENTORY_RESERVED);

        publishOrderConfirmed(orderId);

        // Without this step a confirmed order's reservation would sit ACTIVE until its TTL fired
        // and the sweeper handed back stock that had already been sold.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusOfReservation(reservationId)).isEqualTo("COMMITTED");
            assertThat(onHandOf(sku)).isEqualTo(4);
            assertThat(reservedOf(sku)).isZero();
        });
    }

    @Test
    void concurrentReservationsForTheLastUnit_exactlyOneSucceeds() throws Exception {
        String sku = givenStock(1);
        int contenders = 12;

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(contenders);
        List<Boolean> reserved = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                UUID orderId = UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        startLine.await();
                        inventoryService.reserve(orderId, UUID.randomUUID(),
                                List.of(new InventoryService.RequestedLine(sku, 1)), 600,
                                UUID.randomUUID(), UUID.randomUUID());
                        reserved.add(reservationCountFor(orderId) == 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The real proof of S-6: PostgreSQL, real concurrent transactions, one atomic conditional
        // UPDATE per attempt. Exactly one contender may hold the single unit.
        assertThat(reserved.stream().filter(Boolean::booleanValue).count())
                .as("only one of %d concurrent orders may reserve the last unit", contenders)
                .isEqualTo(1);
        assertThat(reservedOf(sku)).isEqualTo(1);
        assertThat(availableOf(sku)).isZero();
        assertThat(onHandOf(sku)).isEqualTo(1);
    }

    @Test
    void availabilityEndpoint_servesTheDisplayValueAndReportsUnknownSkus() {
        String sku = givenStock(8);
        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        RestTestClient client = builder.build();

        EntityExchangeResult<JsonNode> found = client.get()
                .uri("/api/v1/inventory/availability/" + sku)
                .exchange()
                .returnResult(JsonNode.class);
        assertThat(found.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(found.getResponseBody()).isNotNull();
        assertThat(found.getResponseBody().get("sku").asString()).isEqualTo(sku);
        assertThat(found.getResponseBody().get("available").asInt()).isEqualTo(8);

        EntityExchangeResult<JsonNode> missing = client.get()
                .uri("/api/v1/inventory/availability/SKU-DOES-NOT-EXIST")
                .exchange()
                .returnResult(JsonNode.class);
        assertThat(missing.getStatus().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // ---------------------------------------------------------------------
    // fixtures and helpers
    // ---------------------------------------------------------------------

    /** A SKU unique to the calling test, so tests never contend over the same stock row. */
    private String givenStock(int onHand) {
        String sku = "SKU-IT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into inventory.stock_item (sku, on_hand, reserved) values (?, ?, 0)", sku, onHand);
        return sku;
    }

    private int onHandOf(String sku) {
        return jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ?", Integer.class, sku);
    }

    private int reservedOf(String sku) {
        return jdbc.queryForObject("select reserved from inventory.stock_item where sku = ?", Integer.class, sku);
    }

    private int availableOf(String sku) {
        return jdbc.queryForObject(
                "select on_hand - reserved from inventory.stock_item where sku = ?", Integer.class, sku);
    }

    private String statusOfReservation(UUID reservationId) {
        List<String> statuses = jdbc.queryForList(
                "select status from inventory.reservation where id = ?", String.class, reservationId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private int reservationCountFor(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from inventory.reservation where order_id = ?", Integer.class, orderId);
    }

    private int processedMessageCount() {
        return jdbc.queryForObject(
                "select count(*) from inventory.processed_message where consumer_group = 'inventory-service'",
                Integer.class);
    }

    private void publishReserveStock(UUID orderId, UUID reservationId, String sku, int quantity,
                                     int ttlSeconds) {
        publishRaw(PlatformTopics.COMMANDS_INVENTORY_RESERVE, orderId,
                encodeReserveStock(orderId, reservationId, sku, quantity, ttlSeconds));
    }

    private byte[] encodeReserveStock(UUID orderId, UUID reservationId, String sku, int quantity,
                                      int ttlSeconds) {
        ReserveStock payload = ReserveStock.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setLines(List.of(ReserveLine.newBuilder().setSku(sku).setQuantity(quantity).build()))
                .setTtlSeconds(ttlSeconds)
                .build();
        return encode(MessageKind.COMMAND, MessageTypes.COMMAND_INVENTORY_RESERVE, orderId, payload);
    }

    private void publishReleaseStock(UUID orderId, UUID reservationId) {
        ReleaseStock payload = ReleaseStock.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .build();
        publishRaw(PlatformTopics.COMMANDS_INVENTORY_RELEASE, orderId,
                encode(MessageKind.COMMAND, MessageTypes.COMMAND_INVENTORY_RELEASE, orderId, payload));
    }

    private void publishOrderConfirmed(UUID orderId) {
        OrderConfirmed payload = OrderConfirmed.newBuilder()
                .setOrderId(orderId)
                .setConfirmedAt(Instant.now())
                .build();
        publishRaw(PlatformTopics.EVENTS_ORDER_CONFIRMED, orderId,
                encode(MessageKind.EVENT, MessageTypes.EVENT_ORDER_CONFIRMED, orderId, payload));
    }

    private byte[] encode(MessageKind kind, String type, UUID orderId, SpecificRecordBase payload) {
        return codec.encodeEnvelope(kind, type, UUID.randomUUID(), null, orderId, payload);
    }

    private void publishRaw(String topic, UUID orderId, byte[] envelopeBytes) {
        // Key = order id, matching the orchestrator: every message for one order lands on one
        // partition, which is what keeps per-order ordering (ADR-0017).
        producer.send(new ProducerRecord<>(topic, orderId.toString(), envelopeBytes));
        producer.flush();
    }

    /**
     * Polls the shared verifier for an event of {@code expectedType} concerning {@code orderId}.
     * Non-matching records are buffered rather than discarded, so a later assertion in the same
     * class can still find them — the consumer is never re-subscribed mid-class.
     */
    private Envelope awaitEvent(UUID orderId, String expectedType) {
        String aggregateId = orderId.toString();
        var iterator = buffered.iterator();
        while (iterator.hasNext()) {
            Envelope envelope = iterator.next();
            if (matches(envelope, aggregateId, expectedType)) {
                iterator.remove();
                return envelope;
            }
        }

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            var records = verifier.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, byte[]> record : records) {
                Envelope envelope = codec.decodeEnvelope(record.value());
                if (matches(envelope, aggregateId, expectedType)) {
                    return envelope;
                }
                buffered.add(envelope);
            }
        }
        throw new AssertionError(
                "Expected " + expectedType + " for order " + orderId + " but none arrived in time");
    }

    private boolean matches(Envelope envelope, String aggregateId, String expectedType) {
        return envelope.getAggregateId().equals(aggregateId) && envelope.getType().equals(expectedType);
    }
}
