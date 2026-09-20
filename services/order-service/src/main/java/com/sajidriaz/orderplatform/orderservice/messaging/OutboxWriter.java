package com.sajidriaz.orderplatform.orderservice.messaging;

import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.orderservice.entity.OutboxRecordEntity;
import com.sajidriaz.orderplatform.orderservice.repository.OutboxRecordRepository;
import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends an outbox row in the CURRENT transaction (ADR-0004). Callers (the order
 * creation use case, the saga orchestrator) must invoke this from within an existing
 * {@code @Transactional} method so the business change and the intent to publish
 * commit atomically.
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
     * Encode {@code payload} into a new {@code Envelope} and append it to the outbox,
     * to be published to {@code topic} by the relay.
     *
     * @param kind          COMMAND or EVENT (ADR-0010)
     * @param type          fully-qualified message type, e.g. {@code events.order.created}
     * @param topic         concrete Kafka topic, e.g. {@code events.order.created.v1}
     * @param correlationId ties all messages of this business flow (the order) together
     * @param causationId   the message that directly caused this one (nullable for the first message)
     * @param aggregateId   the order id; becomes the Kafka partition key
     * @param payload       the Avro-specific record body
     */
    public void append(MessageKind kind, String type, String topic, UUID correlationId, UUID causationId,
                        UUID aggregateId, SpecificRecordBase payload) {
        byte[] envelopeBytes = envelopeCodec.encodeEnvelope(kind, type, correlationId, causationId, aggregateId, payload);
        OutboxRecordEntity record = new OutboxRecordEntity(aggregateId, type, kind.name(), topic, envelopeBytes);
        outboxRecordRepository.save(record);
    }
}
