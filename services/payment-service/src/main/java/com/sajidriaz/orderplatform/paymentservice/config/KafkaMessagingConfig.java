package com.sajidriaz.orderplatform.paymentservice.config;

import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
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

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Kafka producer/consumer wiring. The Envelope (and the Avro payload it carries) is serialized
 * to plain bytes in-process by {@link EnvelopeCodec}; Kafka transports opaque {@code byte[]}
 * only — deliberately not the Confluent schema-registry serializer, so the build resolves from
 * Maven Central alone (ADR-0010's note on the envelope payload being {@code bytes}).
 *
 * <p>Producer settings follow ADR-0014 §2 ({@code acks=all} + idempotence); consumer settings
 * follow ADR-0014 §1 (manual ack, committed only after the payment state change and the
 * {@code processed_message} dedup insert commit together, ADR-0005).
 */
@Configuration
public class KafkaMessagingConfig {

    /** Retries AFTER the initial delivery, so 4 retries = 5 total attempts (ADR-0014 §3). */
    private static final int MAX_RETRIES = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long BACKOFF_JITTER_MS = 250L;

    /**
     * Failures retrying cannot fix, so they skip the backoff ladder and are dead-lettered on the
     * first attempt (ADR-0006's "permanent technical" class): an undecodable envelope, an Avro
     * schema mismatch, a malformed aggregate id, a reference to something that does not exist.
     *
     * <p>Note what is <em>not</em> here: a declined payment. That is a normal business outcome
     * which becomes a {@code PaymentDeclined} reply and is acknowledged as successfully
     * processed — never retried, never dead-lettered (ADR-0006).
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Exception>[] NON_RETRYABLE = new Class[] {
            DeserializationException.class,
            AvroRuntimeException.class,
            UncheckedIOException.class,
            IllegalArgumentException.class,
            NoSuchElementException.class
    };

    /**
     * The shared Avro codec, promoted to common-lib so the orchestrator and both participants
     * cannot drift on the wire format. Declared as a bean here because common-lib carries no
     * Spring dependency of its own.
     */
    @Bean
    public EnvelopeCodec envelopeCodec() {
        return new EnvelopeCodec();
    }

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
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate(
            ProducerFactory<String, byte[]> byteArrayProducerFactory) {
        return new KafkaTemplate<>(byteArrayProducerFactory);
    }

    /**
     * Forces the producer's metadata bootstrap at startup rather than on the first outbox
     * publish, which would otherwise happen inside the relay's transaction while it holds both a
     * row lock and a pooled JDBC connection.
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton eagerKafkaProducerInitializer(
            KafkaTemplate<String, byte[]> byteArrayKafkaTemplate) {
        return () -> {
            var producer = byteArrayKafkaTemplate.getProducerFactory().createProducer();
            try {
                producer.partitionsFor(PlatformTopics.EVENTS_PAYMENT_AUTHORIZED);
            } catch (Exception ignored) {
                // Best-effort warm-up only; a real send will retry and report failures.
            } finally {
                producer.close();
            }
        };
    }

    @Bean
    public ConsumerFactory<String, byte[]> byteArrayConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:payment-service}") String groupId) {
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
     * Routes an unprocessable message to this consumer group's dead-letter topic,
     * {@code <topic>.payment-service.DLT} (ADR-0006), with Spring's failure-metadata headers
     * so the DLQ is triageable rather than a black hole.
     *
     * <p>Partition {@code -1} lets the broker partition the dead letter by key instead of
     * mirroring the source partition number, which fails outright whenever the DLT has fewer
     * partitions than the source topic.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, byte[]> byteArrayKafkaTemplate,
            @Value("${spring.kafka.consumer.group-id:payment-service}") String groupId) {
        return new DeadLetterPublishingRecoverer(byteArrayKafkaTemplate,
                (record, exception) -> new TopicPartition(
                        PlatformTopics.deadLetterTopicFor(record.topic(), groupId), -1));
    }

    /**
     * Consumer error handling per ADR-0006 + ADR-0014 §3: transient technical failures are
     * retried with exponential backoff (1s, doubling, capped at 60s, 5 attempts) and only then
     * dead-lettered; permanent ones go straight to the DLQ.
     *
     * <p>DEVIATION FROM ADR-0014 §3 (deliberate, Phase 1, matching order-service): these retries
     * are <em>blocking</em> — redelivered on the same partition rather than forwarded through
     * {@code -retry-1s}/{@code -retry-10s}/{@code -retry-1m} topics. The classification rules and
     * the DLQ contract, the parts that affect correctness, are fully implemented; the
     * non-blocking topology is an availability optimisation that roughly quadruples topic count.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_BACKOFF_MS);
        backOff.setMultiplier(BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        backOff.setJitter(BACKOFF_JITTER_MS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NON_RETRYABLE);
        // Manual ack mode: the container must still advance past a record the error handler has
        // finished with (recovered to the DLQ), or it would be redelivered forever.
        handler.setAckAfterHandle(true);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> byteArrayConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(byteArrayConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
