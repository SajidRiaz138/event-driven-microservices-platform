package com.sajidriaz.orderplatform.paymentservice;

import com.sajidriaz.orderplatform.commands.payment.AuthorizePayment;
import com.sajidriaz.orderplatform.commands.payment.CapturePayment;
import com.sajidriaz.orderplatform.commands.payment.RefundPayment;
import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptureUnknown;
import com.sajidriaz.orderplatform.events.payment.PaymentCaptured;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment-service against real Postgres and Kafka: the participant side of the payment contract.
 *
 * <p>Plays the orchestrator's part by publishing AuthorizePayment/CapturePayment/RefundPayment
 * commands exactly as order-service does — same envelope, same encoder, same partition key — and
 * asserts the emitted reply events, the persisted operations, and the reconciliation outcome. Covers
 * S-1 (authorize then capture), S-3 (decline), and S-17 (lost capture response resolving both ways).
 *
 * <p>The provider outcome is selected by the payment-method token, so each scenario is deterministic
 * rather than dependent on timing.
 */
@SpringBootTest (webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable (named = "DOCKER_AVAILABLE", matches = "true")
class PaymentSagaIT
{

    private static final long AMOUNT_MINOR_UNITS = 1999L;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("orderdb")
            .withUsername("payment_svc")
            .withPassword("testpass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Tighten the outbox relay so reply events reach Kafka quickly, while staying close to the
        // production default (500ms).
        registry.add("order-platform.outbox.relay.fixed-delay-ms", () -> "300");
        // Reconciliation runs on its normal schedule — the UNKNOWN scenarios are meant to be resolved
        // by the real worker, not by a test calling it directly.
        registry.add("payment.reconciliation.fixed-delay-ms", () -> "500");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private static KafkaProducer<String, byte[]> producer;
    private static KafkaConsumer<String, byte[]> verifier;
    private static final List<Envelope> buffered = new ArrayList<>();
    private final EnvelopeCodec codec = new EnvelopeCodec();

    @BeforeAll
    static void startKafkaClients()
    {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-it-verifier");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        verifier = new KafkaConsumer<>(consumerProps);
        verifier.subscribe(List.of(
                PlatformTopics.EVENTS_PAYMENT_AUTHORIZED,
                PlatformTopics.EVENTS_PAYMENT_DECLINED,
                PlatformTopics.EVENTS_PAYMENT_CAPTURED,
                PlatformTopics.EVENTS_PAYMENT_CAPTURE_FAILED,
                PlatformTopics.EVENTS_PAYMENT_CAPTURE_UNKNOWN,
                PlatformTopics.EVENTS_PAYMENT_REFUNDED));
    }

    @AfterAll
    static void stopKafkaClients()
    {
        if (producer != null)
        {
            producer.close();
        }
        if (verifier != null)
        {
            verifier.close();
        }
    }

    @Test
    void authorizeThenCapture_emitsAuthorizedThenCaptured()
    {
        UUID orderId = UUID.randomUUID();

        publishAuthorize(orderId, "pi_ok");
        Envelope authorized = awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);
        assertThat(authorized.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(authorized.getMessageKind()).isEqualTo(MessageKind.EVENT);
        assertThat(statusOf(orderId, "AUTHORIZE")).containsExactly("SUCCEEDED");

        publishCapture(orderId);
        Envelope captured = awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURED);
        PaymentCaptured payload = codec.decodePayload(captured, PaymentCaptured.class);
        assertThat(payload.getOrderId()).isEqualTo(orderId);
        assertThat(payload.getAmount().getMinorUnits()).isEqualTo(AMOUNT_MINOR_UNITS);
        // The provider reference is what makes this capture verifiable against the provider later.
        assertThat(payload.getProviderReference()).startsWith("cap_");
        assertThat(statusOf(orderId, "CAPTURE")).containsExactly("SUCCEEDED");
    }

    @Test
    void authorize_withADecliningInstrument_emitsPaymentDeclined()
    {
        UUID orderId = UUID.randomUUID();

        publishAuthorize(orderId, "pi_decline");

        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_DECLINED);
        assertThat(statusOf(orderId, "AUTHORIZE")).containsExactly("FAILED");
        // A decline is a business outcome, not a poison message: the command was processed
        // successfully and nothing was dead-lettered.
        assertThat(processedMessageCount()).isPositive();
    }

    @Test
    void capture_whenTheProviderResponseIsLostButFundsMoved_goesUnknownThenReconcilesToCaptured()
    {
        UUID orderId = UUID.randomUUID();

        publishAuthorize(orderId, "pi_capture_timeout_captured");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);

        publishCapture(orderId);

        // First, the ambiguous outcome is recorded and announced as UNKNOWN — not failed, not
        // succeeded (ADR-0016 §2, scenario S-17). The awaited PaymentCaptureUnknown event is the
        // authoritative proof the capture passed through UNKNOWN; the persisted status is only
        // asserted loosely because the 500ms reconciliation scheduler may already have resolved it
        // to SUCCEEDED by the time we read the row (a benign race — the event above is the invariant).
        Envelope unknown = awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURE_UNKNOWN);
        PaymentCaptureUnknown unknownPayload = codec.decodePayload(unknown, PaymentCaptureUnknown.class);
        assertThat(unknownPayload.getOrderId()).isEqualTo(orderId);
        assertThat(statusOf(orderId, "CAPTURE")).containsAnyOf("UNKNOWN", "SUCCEEDED");

        // Then the reconciliation worker asks the provider with the same idempotency key, learns the
        // funds did move, and publishes the pivot event the direct path would have published.
        Envelope captured = awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURED);
        assertThat(codec.decodePayload(captured, PaymentCaptured.class).getOrderId()).isEqualTo(orderId);
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(orderId, "CAPTURE")).containsExactly("SUCCEEDED"));

        // Exactly one capture operation exists throughout: no second charge was ever attempted.
        assertThat(operationCount(orderId, "CAPTURE")).isEqualTo(1);
    }

    @Test
    void capture_whenTheResponseIsLostAndFundsDidNotMove_reconcilesToCaptureFailed()
    {
        UUID orderId = UUID.randomUUID();

        publishAuthorize(orderId, "pi_capture_timeout_lost");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);

        publishCapture(orderId);
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURE_UNKNOWN);

        // The identical ambiguous signal resolving the other way. This is why an UNKNOWN capture can
        // never be assumed to have succeeded — or to have failed.
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURE_FAILED);
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(orderId, "CAPTURE")).containsExactly("FAILED"));
        assertThat(operationCount(orderId, "CAPTURE")).isEqualTo(1);
    }

    @Test
    void capture_whoseOutcomeTheProviderCannotEstablish_staysUnknownAndIsNeverGuessed()
    {
        UUID orderId = UUID.randomUUID();

        publishAuthorize(orderId, "pi_capture_timeout_unanswerable");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);

        publishCapture(orderId);
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURE_UNKNOWN);

        // Give reconciliation several cycles. It must keep the operation UNKNOWN and escalate rather
        // than resolve it: running out of patience is not evidence about where the money is.
        Awaitility.await()
                .during(Duration.ofSeconds(4))
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderId, "CAPTURE")).containsExactly("UNKNOWN"));
        assertThat(reconcileAttempts(orderId)).isPositive();
    }

    @Test
    void refund_isRefusedWhileTheCaptureOutcomeIsUnknown()
    {
        UUID orderId = UUID.randomUUID();
        publishAuthorize(orderId, "pi_capture_timeout_unanswerable");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);
        publishCapture(orderId);
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURE_UNKNOWN);

        publishRefund(orderId);

        // No refund operation may exist: returning money that may never have been taken is exactly
        // what ADR-0016 forbids.
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(operationCount(orderId, "REFUND")).isZero());
    }

    @Test
    void refund_afterAnEstablishedCapture_emitsPaymentRefunded()
    {
        UUID orderId = UUID.randomUUID();
        publishAuthorize(orderId, "pi_ok");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);
        publishCapture(orderId);
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_CAPTURED);

        publishRefund(orderId);

        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_REFUNDED);
        assertThat(statusOf(orderId, "REFUND")).containsExactly("SUCCEEDED");
    }

    @Test
    void redeliveredAuthorizeCommand_authorizesOnlyOnce()
    {
        UUID orderId = UUID.randomUUID();

        // The same bytes twice: identical messageId, which is what a consumer crash before the offset
        // commit produces on restart (S-10). Re-applying a payment command is how systems charge
        // twice, so this must be a no-op.
        byte[] command = encodeAuthorize(orderId, "pi_ok");
        publishRaw(PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE, orderId, command);
        publishRaw(PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE, orderId, command);

        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);
        Awaitility.await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(operationCount(orderId, "AUTHORIZE")).isEqualTo(1));
        assertThat(intentCount(orderId)).isEqualTo(1);
    }

    @Test
    void operationsEndpoint_exposesTheOperationsForAnOrder()
    {
        UUID orderId = UUID.randomUUID();
        publishAuthorize(orderId, "pi_ok");
        awaitEvent(orderId, MessageTypes.EVENT_PAYMENT_AUTHORIZED);

        RestTestClient.Builder<?> builder = RestTestClient.bindToServer();
        builder.baseUrl("http://localhost:" + port);
        RestTestClient client = builder.build();

        EntityExchangeResult<JsonNode> found = client.get()
                .uri("/api/v1/payments/orders/" + orderId + "/operations")
                .exchange()
                .returnResult(JsonNode.class);
        assertThat(found.getStatus().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(found.getResponseBody()).isNotNull();
        assertThat(found.getResponseBody().get(0).get("type").asString()).isEqualTo("AUTHORIZE");
        assertThat(found.getResponseBody().get(0).get("status").asString()).isEqualTo("SUCCEEDED");

        EntityExchangeResult<JsonNode> missing = client.get()
                .uri("/api/v1/payments/orders/" + UUID.randomUUID() + "/operations")
                .exchange()
                .returnResult(JsonNode.class);
        assertThat(missing.getStatus().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // ---------------------------------------------------------------------
    // database helpers
    // ---------------------------------------------------------------------

    private List<String> statusOf(UUID orderId, String operationType)
    {
        return jdbc.queryForList("""
                select o.status from payment.payment_operation o
                join payment.payment_attempt a on a.id = o.attempt_id
                join payment.payment_intent i on i.id = a.intent_id
                where i.order_id = ? and o.operation_type = ?
                """, String.class, orderId, operationType);
    }

    private int operationCount(UUID orderId, String operationType)
    {
        return jdbc.queryForObject("""
                select count(*) from payment.payment_operation o
                join payment.payment_attempt a on a.id = o.attempt_id
                join payment.payment_intent i on i.id = a.intent_id
                where i.order_id = ? and o.operation_type = ?
                """, Integer.class, orderId, operationType);
    }

    private int reconcileAttempts(UUID orderId)
    {
        return jdbc.queryForObject("""
                select coalesce(max(o.reconcile_attempts), 0) from payment.payment_operation o
                join payment.payment_attempt a on a.id = o.attempt_id
                join payment.payment_intent i on i.id = a.intent_id
                where i.order_id = ?
                """, Integer.class, orderId);
    }

    private int intentCount(UUID orderId)
    {
        return jdbc.queryForObject(
                "select count(*) from payment.payment_intent where order_id = ?", Integer.class, orderId);
    }

    private int processedMessageCount()
    {
        return jdbc.queryForObject(
                "select count(*) from payment.processed_message where consumer_group = 'payment-service'",
                Integer.class);
    }

    // ---------------------------------------------------------------------
    // Kafka helpers — standing in for order-service
    // ---------------------------------------------------------------------

    private void publishAuthorize(UUID orderId, String paymentMethodToken)
    {
        publishRaw(PlatformTopics.COMMANDS_PAYMENT_AUTHORIZE, orderId,
                encodeAuthorize(orderId, paymentMethodToken));
    }

    private byte[] encodeAuthorize(UUID orderId, String paymentMethodToken)
    {
        AuthorizePayment payload = AuthorizePayment.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(UUID.randomUUID())
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentAttemptId(UUID.randomUUID())
                .setAmount(com.sajidriaz.orderplatform.common.Money.newBuilder()
                        .setMinorUnits(AMOUNT_MINOR_UNITS)
                        .setCurrency("USD")
                        .build())
                .setPaymentMethodToken(paymentMethodToken)
                .build();
        return encode(MessageKind.COMMAND, MessageTypes.COMMAND_PAYMENT_AUTHORIZE, orderId, payload);
    }

    private void publishCapture(UUID orderId)
    {
        // Note the ids: the orchestrator mints fresh ones per command, so these deliberately do NOT
        // match the authorize command's. The service must correlate on the order id.
        CapturePayment payload = CapturePayment.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentAttemptId(UUID.randomUUID())
                .build();
        publishRaw(PlatformTopics.COMMANDS_PAYMENT_CAPTURE, orderId,
                encode(MessageKind.COMMAND, MessageTypes.COMMAND_PAYMENT_CAPTURE, orderId, payload));
    }

    private void publishRefund(UUID orderId)
    {
        RefundPayment payload = RefundPayment.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(UUID.randomUUID())
                .setPaymentOperationId(UUID.randomUUID())
                .setAmount(com.sajidriaz.orderplatform.common.Money.newBuilder()
                        .setMinorUnits(AMOUNT_MINOR_UNITS)
                        .setCurrency("USD")
                        .build())
                .build();
        publishRaw(PlatformTopics.COMMANDS_PAYMENT_REFUND, orderId,
                encode(MessageKind.COMMAND, MessageTypes.COMMAND_PAYMENT_REFUND, orderId, payload));
    }

    private byte[] encode(MessageKind kind, String type, UUID orderId, SpecificRecordBase payload)
    {
        return codec.encodeEnvelope(kind, type, UUID.randomUUID(), null, orderId, payload);
    }

    private void publishRaw(String topic, UUID orderId, byte[] envelopeBytes)
    {
        // Key = order id, matching the orchestrator: every message for one order lands on one
        // partition (ADR-0017).
        producer.send(new ProducerRecord<>(topic, orderId.toString(), envelopeBytes));
        producer.flush();
    }

    /**
     * Polls the shared verifier for an event of {@code expectedType} concerning {@code orderId}.
     * Non-matching records are buffered rather than discarded so a later assertion in the same class
     * can still find them.
     */
    private Envelope awaitEvent(UUID orderId, String expectedType)
    {
        String aggregateId = orderId.toString();
        var iterator = buffered.iterator();
        while (iterator.hasNext())
        {
            Envelope envelope = iterator.next();
            if (matches(envelope, aggregateId, expectedType))
            {
                iterator.remove();
                return envelope;
            }
        }

        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline)
        {
            var records = verifier.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, byte[]> record : records)
            {
                Envelope envelope = codec.decodeEnvelope(record.value());
                if (matches(envelope, aggregateId, expectedType))
                {
                    return envelope;
                }
                buffered.add(envelope);
            }
        }
        throw new AssertionError(
                "Expected " + expectedType + " for order " + orderId + " but none arrived in time");
    }

    private boolean matches(Envelope envelope, String aggregateId, String expectedType)
    {
        return envelope.getAggregateId().equals(aggregateId) && envelope.getType().equals(expectedType);
    }
}
