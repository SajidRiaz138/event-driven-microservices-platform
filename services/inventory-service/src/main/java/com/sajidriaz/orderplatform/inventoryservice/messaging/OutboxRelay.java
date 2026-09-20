package com.sajidriaz.orderplatform.inventoryservice.messaging;

import com.sajidriaz.orderplatform.inventoryservice.entity.OutboxRecordEntity;
import com.sajidriaz.orderplatform.inventoryservice.repository.OutboxRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The outbox relay (ADR-0004): polls {@code PENDING} rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} so several replicas can drain the outbox
 * concurrently without double-publishing, publishes each keyed by {@code aggregateId} (the
 * order id — per-order ordering, ADR-0010/0017), and marks it {@code SENT}.
 *
 * <p>Publishing is at-least-once by design: a crash after Kafka acknowledges but before the row
 * is marked {@code SENT} means the next poll republishes it. The bytes are identical, so the
 * envelope's {@code messageId} is identical, so the consumer's dedup table recognises it
 * (ADR-0005). Chasing exactly-once here would buy nothing.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * How long a single publish may hold its DB connection. A row is locked for the duration of
     * the relay transaction, and Kafka's own delivery.timeout.ms defaults to two minutes — an
     * unbounded {@code get()} would starve a 10-connection pool for that long. A send that
     * overruns is simply treated as a failure and retried next poll.
     */
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxRecordRepository outboxRecordRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRecordRepository outboxRecordRepository,
                       KafkaTemplate<String, byte[]> kafkaTemplate,
                       @Value("${order-platform.outbox.relay.batch-size:50}") int batchSize,
                       @Value("${order-platform.outbox.relay.max-attempts:50}") int maxAttempts) {
        this.outboxRecordRepository = outboxRecordRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
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
            kafkaTemplate.send(record.getTopic(), record.getAggregateId().toString(), record.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            record.markSent(Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted publishing outbox record {}; will retry on next poll", record.getId(), e);
        } catch (Exception e) {
            int attempts = record.recordFailedAttempt();
            if (attempts >= maxAttempts) {
                // Retrying forever with no signal is how an undeliverable row becomes invisible.
                // FAILED is terminal for the relay and is what monitoring should alert on.
                record.markFailed();
                log.error("Giving up on outbox record {} to topic {} after {} attempts; marked FAILED "
                                + "and requires operator attention",
                        record.getId(), record.getTopic(), attempts, e);
            } else {
                log.warn("Failed to publish outbox record {} to topic {} (attempt {}); will retry on next poll",
                        record.getId(), record.getTopic(), attempts, e);
            }
        }
    }
}
