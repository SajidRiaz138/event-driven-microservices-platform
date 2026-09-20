package com.sajidriaz.orderplatform.orderservice.config;

import org.apache.avro.AvroRuntimeException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import com.sajidriaz.orderplatform.orderservice.messaging.Topics;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Kafka producer/consumer wiring. The Envelope (and the Avro payloads it carries) is
 * serialized to plain bytes in-process ({@code EnvelopeCodec}); Kafka transports
 * opaque {@code byte[]} only, via {@link ByteArraySerializer}/{@link ByteArrayDeserializer}
 * — deliberately not the Confluent schema-registry serializer (see the order-service
 * pom and ADR-0010's note on the envelope payload being {@code bytes}).
 *
 * <p>Producer settings follow ADR-0014 §2: {@code acks=all} + {@code enable.idempotence}
 * for safe publishing. Consumer settings follow ADR-0014 §1: manual acknowledgement,
 * committed only after the business change + {@code processed_message} dedup insert
 * commit in the same DB transaction (ADR-0005).
 */
@Configuration
public class KafkaMessagingConfig {

    /**
     * Retries AFTER the initial delivery, so 4 retries = 5 total attempts before the
     * dead letter (ADR-0014 §3: "max 5 attempts, then the message is routed to the DLQ").
     */
    private static final int MAX_RETRIES = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long BACKOFF_JITTER_MS = 250L;

    /**
     * Failures that retrying cannot fix, so they skip the backoff ladder and are
     * dead-lettered on the first attempt (ADR-0006's "permanent technical" class):
     * a corrupt/undecodable envelope, an Avro schema mismatch, a malformed aggregate id,
     * or a reply naming an order/saga this service has no row for.
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Exception>[] NON_RETRYABLE = new Class[] {
            DeserializationException.class,
            AvroRuntimeException.class,
            UncheckedIOException.class,
            IllegalArgumentException.class,
            NoSuchElementException.class
    };

    @Bean
    public ProducerFactory<String, byte[]> byteArrayProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate(ProducerFactory<String, byte[]> byteArrayProducerFactory) {
        return new KafkaTemplate<>(byteArrayProducerFactory);
    }

    /**
     * Forces the Kafka producer (and its metadata bootstrap against the broker) to
     * initialize eagerly at application startup, rather than lazily on the first
     * outbox publish. Without this, the first HTTP request that creates an order
     * pays the producer's full connection/bootstrap cost inside its own
     * {@code @Transactional} method — extending how long that request holds its
     * pooled JDBC connection for no correctness reason.
     */
    /**
     * Forces the Kafka producer (and its metadata bootstrap against the broker) to
     * initialize eagerly at application startup, rather than lazily on the first
     * outbox publish. Without this, the first HTTP request that creates an order
     * pays the producer's full connection/bootstrap cost inside its own
     * {@code @Transactional} method — extending how long that request holds its
     * pooled JDBC connection for no correctness reason.
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton eagerKafkaProducerInitializer(
            KafkaTemplate<String, byte[]> byteArrayKafkaTemplate) {
        return () -> {
            var producer = byteArrayKafkaTemplate.getProducerFactory().createProducer();
            try {
                // partitionsFor() forces the full metadata fetch (topic/broker
                // discovery), not just TCP connect — this is the part that is
                // otherwise deferred to the very first send() and can take a couple
                // of seconds against a freshly-started broker.
                producer.partitionsFor(Topics.EVENTS_ORDER_CREATED);
            } catch (Exception ignored) {
                // Best-effort warm-up only; a real send will retry/report failures.
            } finally {
                producer.close();
            }
        };
    }

    @Bean
    public ConsumerFactory<String, byte[]> byteArrayConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:order-service}") String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Routes a message that cannot be processed to this consumer group's dead-letter
     * topic, {@code <topic>.order-service.DLT} (ADR-0006). Spring's recoverer copies the
     * original record plus failure metadata headers (exception, stack trace, original
     * topic/partition/offset) so the DLQ is triageable rather than a black hole.
     *
     * <p>Partition {@code -1} lets the broker partition the dead letter by key instead of
     * mirroring the source partition number, which would fail outright whenever the DLT
     * has fewer partitions than the source topic.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, byte[]> byteArrayKafkaTemplate,
            @Value("${spring.kafka.consumer.group-id:order-service}") String groupId) {
        return new DeadLetterPublishingRecoverer(byteArrayKafkaTemplate,
                (record, exception) -> new TopicPartition(
                        Topics.deadLetterTopicFor(record.topic(), groupId), -1));
    }

    /**
     * Consumer error handling per ADR-0006 + ADR-0014 §3.
     *
     * <p><strong>Classification is the point of this bean.</strong> Three outcomes:
     * <ul>
     *   <li><em>Business rejections</em> — insufficient stock, payment declined — never
     *       reach here at all. They arrive as ordinary reply events
     *       ({@code StockReservationFailed}, {@code PaymentDeclined}), drive compensation,
     *       and are acknowledged as successfully processed. ADR-0006 is explicit that
     *       these are normal saga outcomes, never poison, and must never be retried or
     *       dead-lettered.
     *   <li><em>Permanent technical</em> — a corrupt envelope, an Avro schema mismatch, a
     *       malformed aggregate id, a reply for an order this service does not know —
     *       cannot succeed however often it is retried, so it goes straight to the DLQ
     *       with no retries wasted ({@link #NON_RETRYABLE}).
     *   <li><em>Transient technical</em> — a DB blip, a broker timeout — is retried with
     *       exponential backoff (1s, doubling, capped at 60s, 5 attempts total) and only
     *       then dead-lettered.
     * </ul>
     *
     * <p>DEVIATION FROM ADR-0006 (deliberate, Phase 1): these retries are <em>blocking</em>
     * — delivery is retried on the same partition rather than forwarded to the
     * {@code -retry-1s}/{@code -retry-10s}/{@code -retry-1m} non-blocking retry topics
     * ADR-0014 §3 prescribes. The classification rules and the DLQ contract, which are
     * the parts that affect correctness, are fully implemented; the non-blocking topology
     * is an availability optimisation that multiplies topic count by roughly four and is
     * recorded as a follow-up.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_BACKOFF_MS);
        backOff.setMultiplier(BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        // Full jitter is what ADR-0014 asks for; Spring's ExponentialBackOff jitter is
        // a bounded random offset per interval, which serves the same purpose of not
        // synchronising retries across consumers into a thundering herd.
        backOff.setJitter(BACKOFF_JITTER_MS);

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        handler.addNotRetryableExceptions(NON_RETRYABLE);
        // Manual ack mode: the container must still advance past a record the error
        // handler has finished with (recovered to the DLQ), or that record would be
        // redelivered forever.
        handler.setAckAfterHandle(true);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> byteArrayConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(byteArrayConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
