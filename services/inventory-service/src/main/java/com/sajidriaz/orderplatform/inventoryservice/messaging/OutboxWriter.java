package com.sajidriaz.orderplatform.inventoryservice.messaging;

import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.inventoryservice.entity.OutboxRecordEntity;
import com.sajidriaz.orderplatform.inventoryservice.repository.OutboxRecordRepository;
import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends an outbox row in the CURRENT transaction (ADR-0004). Callers must invoke this from
 * within an existing {@code @Transactional} method so the stock change and the intent to
 * announce it commit atomically — that is the whole point of the pattern: there is no window in
 * which stock moved but no event will ever be published, and none in which an event is published
 * for a change that rolled back.
 */
@Component
public class OutboxWriter {

    private final OutboxRecordRepository outboxRecordRepository;
    private final EnvelopeCodec envelopeCodec;

    public OutboxWriter(OutboxRecordRepository outboxRecordRepository, EnvelopeCodec envelopeCodec) {
        this.outboxRecordRepository = outboxRecordRepository;
        this.envelopeCodec = envelopeCodec;
    }

    /**
     * Encode {@code payload} into a new {@code Envelope} and append it to the outbox for the
     * relay to publish to {@code topic}.
     *
     * @param kind          COMMAND or EVENT (ADR-0010)
     * @param type          fully-qualified message type, e.g. {@code events.inventory.reserved}
     * @param topic         concrete Kafka topic, e.g. {@code events.inventory.reserved.v1}
     * @param correlationId echoed from the command being replied to, so the whole order flow
     *                      stays joined up in logs and traces
     * @param causationId   the {@code messageId} of the command that caused this reply
     * @param aggregateId   the order id — becomes the Kafka partition key and the only handle
     *                      the orchestrator has for correlating a reply to its saga
     */
    public void append(MessageKind kind, String type, String topic, UUID correlationId, UUID causationId,
                       UUID aggregateId, SpecificRecordBase payload) {
        byte[] envelopeBytes =
                envelopeCodec.encodeEnvelope(kind, type, correlationId, causationId, aggregateId, payload);
        outboxRecordRepository.save(
                new OutboxRecordEntity(aggregateId, type, kind.name(), topic, envelopeBytes));
    }
}
