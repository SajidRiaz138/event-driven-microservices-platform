package com.sajidriaz.orderplatform.orderservice.messaging;

import com.sajidriaz.orderplatform.orderservice.entity.OutboxRecordEntity;
import com.sajidriaz.orderplatform.orderservice.repository.OutboxRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The outbox relay (ADR-0004): polls {@code PENDING} outbox rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} so multiple replicas can drain the outbox
 * concurrently without double-publishing, publishes each to its Kafka topic keyed by
 * {@code aggregateId} (per-order ordering, ADR-0010), and marks it {@code SENT}.
 *
 * <p>Publishing is at-least-once by design: if the process crashes after Kafka
 * acknowledges the send but before the row is marked {@code SENT}, the next poll
 * re-publishes it. Consumers deduplicate (ADR-0005) — this relay does not try to
 * achieve exactly-once.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRecordRepository outboxRecordRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(OutboxRecordRepository outboxRecordRepository,
                        KafkaTemplate<String, byte[]> kafkaTemplate,
                        @Value("${order-platform.outbox.relay.batch-size:50}") int batchSize) {
        this.outboxRecordRepository = outboxRecordRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${order-platform.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void relayPendingRecords() {
        List<OutboxRecordEntity> batch = outboxRecordRepository.lockNextBatch(batchSize);
        for (OutboxRecordEntity record : batch) {
            publish(record);
        }
    }

    private void publish(OutboxRecordEntity record) {
        try {
            // Bounded wait, not an unbounded blocking .get(): this call runs inside a
            // DB transaction holding a pooled connection (ADR-0004's SELECT ... FOR
            // UPDATE SKIP LOCKED lock), so it must never block for as long as Kafka's
            // own delivery.timeout.ms (up to 120s default) — that would starve the
            // Hikari pool for every other request/scheduled task sharing it. A send
            // that does not complete within the bound is treated as a failure: the
            // row stays PENDING and is retried on the next poll (at-least-once,
            // ADR-0014 §2), same outcome as any other publish failure.
            kafkaTemplate.send(record.getTopic(), record.getAggregateId().toString(), record.getPayload())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            record.markSent(Instant.now());
        } catch (Exception e) {
            // Leave the row PENDING; the next poll retries. This inherits at-least-once
            // publishing without extra machinery (ADR-0014 §2).
            log.warn("Failed to publish outbox record {} to topic {}; will retry on next poll",
                    record.getId(), record.getTopic(), e);
        }
    }
}
