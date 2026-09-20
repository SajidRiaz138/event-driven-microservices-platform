package com.sajidriaz.orderplatform.orderservice.saga;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.orderservice.entity.ProcessedMessageEntity;
import com.sajidriaz.orderplatform.orderservice.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.orderservice.messaging.Topics;
import com.sajidriaz.orderplatform.orderservice.observability.TraceparentContext;
import com.sajidriaz.orderplatform.orderservice.repository.ProcessedMessageRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes the saga's reply events (ADR-0003) and dispatches each to the
 * {@link SagaOrchestrator} state machine. Every handler:
 *
 * <ol>
 *   <li>decodes the {@link Envelope};
 *   <li>inside ONE {@code @Transactional} method, inserts the
 *       {@code processed_message(messageId, "order-service")} dedup row and applies
 *       the state transition — so a redelivered message is recognised and skipped
 *       without reapplying the effect (ADR-0005 layer 2, S-10);
 *   <li>acknowledges the offset only AFTER that transaction commits (manual ack,
 *       ADR-0014 §1) — a crash before this line causes Kafka to redeliver, which is
 *       safe because of the dedup row.
 * </ol>
 *
 * <p>payment-service and inventory-service do not exist yet (Phase 1); in production
 * they publish these reply events. Integration tests simulate them by publishing
 * directly to these topics.
 */
@Component
public class SagaReplyListener {

    private static final Logger log = LoggerFactory.getLogger(SagaReplyListener.class);
    static final String CONSUMER_GROUP = "order-service";

    private final SagaOrchestrator sagaOrchestrator;
    private final ProcessedMessageRepository processedMessageRepository;
    private final EnvelopeCodec envelopeCodec;

    public SagaReplyListener(SagaOrchestrator sagaOrchestrator,
                              ProcessedMessageRepository processedMessageRepository,
                              EnvelopeCodec envelopeCodec) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.processedMessageRepository = processedMessageRepository;
        this.envelopeCodec = envelopeCodec;
    }

    @KafkaListener(topics = {
            Topics.EVENTS_INVENTORY_RESERVED,
            Topics.EVENTS_INVENTORY_RESERVATION_FAILED,
            Topics.EVENTS_INVENTORY_RELEASED,
            Topics.EVENTS_PAYMENT_AUTHORIZED,
            Topics.EVENTS_PAYMENT_DECLINED,
            Topics.EVENTS_PAYMENT_CAPTURED,
            Topics.EVENTS_PAYMENT_CAPTURE_FAILED
    }, groupId = CONSUMER_GROUP)
    public void onReplyEvent(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        Envelope envelope = envelopeCodec.decodeEnvelope(record.value());
        // Adopt the sender's context so logs join up and events produced here carry the same
        // traceparent (ADR-0013). Without this, the orchestrator's own log lines — the ones
        // that say what the saga decided — are the only ones in the flow with no
        // correlationId, which is exactly backwards. Cleared in the finally: a virtual
        // thread must never be left holding stale context.
        CorrelationContext.adoptOrGenerate(String.valueOf(envelope.getCorrelationId()));
        TraceparentContext.set(envelope.getTraceparent());
        CorrelationContext.setTraceId(TraceparentContext.traceIdOf(envelope.getTraceparent()));
        CorrelationContext.setTenant(envelope.getTenantId());
        try {
            applyIfNotProcessed(envelope, e -> dispatch(record.topic(), e));
            ack.acknowledge();
        } finally {
            TraceparentContext.clear();
            CorrelationContext.clear();
        }
    }

    /**
     * Routes a decoded reply event to the matching saga transition by its source
     * topic. A single multi-topic listener (rather than one listener container per
     * topic) joins the {@code order-service} consumer group once instead of six
     * times concurrently at startup — six simultaneous group-joins against a
     * single-node broker were observed to be needlessly heavy for Phase 1's scale.
     */
    private void dispatch(String topic, Envelope envelope) {
        UUID orderId = orderIdOf(envelope);
        UUID messageId = messageIdOf(envelope);
        if (Topics.EVENTS_INVENTORY_RESERVED.equals(topic)) {
            sagaOrchestrator.onStockReserved(orderId, messageId);
        } else if (Topics.EVENTS_INVENTORY_RESERVATION_FAILED.equals(topic)) {
            sagaOrchestrator.onStockReservationFailed(orderId, messageId);
        } else if (Topics.EVENTS_INVENTORY_RELEASED.equals(topic)) {
            sagaOrchestrator.onStockReleased(orderId, messageId);
        } else if (Topics.EVENTS_PAYMENT_AUTHORIZED.equals(topic)) {
            sagaOrchestrator.onPaymentAuthorized(orderId, messageId);
        } else if (Topics.EVENTS_PAYMENT_DECLINED.equals(topic)) {
            sagaOrchestrator.onPaymentDeclined(orderId, messageId);
        } else if (Topics.EVENTS_PAYMENT_CAPTURED.equals(topic)) {
            sagaOrchestrator.onPaymentCaptured(orderId, messageId);
        } else if (Topics.EVENTS_PAYMENT_CAPTURE_FAILED.equals(topic)) {
            sagaOrchestrator.onPaymentCaptureFailed(orderId, messageId);
        } else {
            log.warn("No saga transition mapped for reply event on topic {}", topic);
        }
    }

    /**
     * Applies {@code action} inside one transaction that also inserts the dedup row —
     * unless the message id has already been processed, in which case the action is
     * skipped entirely (idempotent redelivery, S-10).
     */
    @Transactional
    void applyIfNotProcessed(Envelope envelope, java.util.function.Consumer<Envelope> action) {
        UUID messageId = envelope.getMessageId();
        var key = new ProcessedMessageEntity.Key(messageId, CONSUMER_GROUP);
        if (processedMessageRepository.existsById(key)) {
            log.info("Skipping already-processed message {} (type={})", messageId, envelope.getType());
            return;
        }
        action.accept(envelope);
        processedMessageRepository.save(new ProcessedMessageEntity(messageId, CONSUMER_GROUP));
    }

    private UUID orderIdOf(Envelope envelope) {
        return UUID.fromString(envelope.getAggregateId());
    }

    private UUID messageIdOf(Envelope envelope) {
        return envelope.getMessageId();
    }
}
