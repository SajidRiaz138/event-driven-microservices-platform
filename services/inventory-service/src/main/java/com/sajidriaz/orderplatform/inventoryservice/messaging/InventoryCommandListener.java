package com.sajidriaz.orderplatform.inventoryservice.messaging;

import com.sajidriaz.orderplatform.commands.inventory.ReleaseStock;
import com.sajidriaz.orderplatform.commands.inventory.ReserveLine;
import com.sajidriaz.orderplatform.commands.inventory.ReserveStock;
import com.sajidriaz.orderplatform.common.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.common.observability.TraceparentContext;
import com.sajidriaz.orderplatform.events.Envelope;
import com.sajidriaz.orderplatform.inventoryservice.entity.ProcessedMessageEntity;
import com.sajidriaz.orderplatform.inventoryservice.repository.ProcessedMessageRepository;
import com.sajidriaz.orderplatform.inventoryservice.service.InventoryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes the saga commands addressed to inventory, plus the order-confirmed event that
 * completes a sale.
 *
 * <p>One listener over several topics rather than one per topic: each {@code @KafkaListener}
 * joins the consumer group separately, and a group rebalance per topic at startup buys nothing.
 *
 * <p>The offset is acknowledged only after the transaction containing both the stock change and
 * the {@code processed_message} row has committed (ADR-0005). A crash before the ack means
 * redelivery, which the dedup row makes harmless (scenario S-10).
 */
@Component
public class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

    static final String CONSUMER_GROUP = "inventory-service";

    private final InventoryService inventoryService;
    private final ProcessedMessageRepository processedMessageRepository;
    private final EnvelopeCodec envelopeCodec;

    public InventoryCommandListener(InventoryService inventoryService,
                                    ProcessedMessageRepository processedMessageRepository,
                                    EnvelopeCodec envelopeCodec) {
        this.inventoryService = inventoryService;
        this.processedMessageRepository = processedMessageRepository;
        this.envelopeCodec = envelopeCodec;
    }

    @KafkaListener(topics = {
            PlatformTopics.COMMANDS_INVENTORY_RESERVE,
            PlatformTopics.COMMANDS_INVENTORY_RELEASE,
            PlatformTopics.EVENTS_ORDER_CONFIRMED
    }, groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        Envelope envelope = envelopeCodec.decodeEnvelope(record.value());
        // Adopt the sender's context so every log line from here on joins the order's flow, and
        // so events produced while handling this record carry the same traceparent (ADR-0013).
        // Cleared in the finally: a virtual thread must never be left holding stale context.
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

    private void dispatch(String topic, Envelope envelope) {
        UUID orderId = orderIdOf(envelope);
        UUID causationId = envelope.getMessageId();
        UUID correlationId = envelope.getCorrelationId();

        if (PlatformTopics.COMMANDS_INVENTORY_RESERVE.equals(topic)) {
            ReserveStock command = envelopeCodec.decodePayload(envelope, ReserveStock.class);
            inventoryService.reserve(orderId, command.getReservationId(), linesOf(command),
                    command.getTtlSeconds(), correlationId, causationId);
        } else if (PlatformTopics.COMMANDS_INVENTORY_RELEASE.equals(topic)) {
            ReleaseStock command = envelopeCodec.decodePayload(envelope, ReleaseStock.class);
            inventoryService.release(orderId, command.getReservationId(), correlationId, causationId);
        } else if (PlatformTopics.EVENTS_ORDER_CONFIRMED.equals(topic)) {
            // Only the fact that this order is confirmed matters; the payload adds nothing this
            // service needs beyond the aggregate id.
            inventoryService.commitForConfirmedOrder(orderId);
        } else {
            log.warn("No handler mapped for topic {}", topic);
        }
    }

    /**
     * Runs {@code action} unless this message was already processed by this consumer group, and
     * records it as processed — both in ONE transaction with the stock change, which is what makes
     * the dedup trustworthy (ADR-0005). A separate transaction for the marker would leave a window
     * where the effect is applied but not recorded.
     */
    @Transactional
    void applyIfNotProcessed(Envelope envelope, Consumer<Envelope> action) {
        UUID messageId = envelope.getMessageId();
        var key = new ProcessedMessageEntity.Key(messageId, CONSUMER_GROUP);
        if (processedMessageRepository.existsById(key)) {
            log.info("Skipping already-processed message {} (type={})", messageId, envelope.getType());
            return;
        }
        action.accept(envelope);
        processedMessageRepository.save(new ProcessedMessageEntity(messageId, CONSUMER_GROUP));
    }

    private List<InventoryService.RequestedLine> linesOf(ReserveStock command) {
        List<InventoryService.RequestedLine> lines = new ArrayList<>();
        for (ReserveLine line : command.getLines()) {
            lines.add(new InventoryService.RequestedLine(line.getSku(), line.getQuantity()));
        }
        return lines;
    }

    /**
     * The order id, from the envelope's {@code aggregateId} — the partition key, and the one
     * identifier consistent across every message of a flow. A malformed value throws
     * {@link IllegalArgumentException}, which the error handler classifies as permanent: no amount
     * of retrying will make it parse, so it goes straight to the DLQ.
     */
    private UUID orderIdOf(Envelope envelope) {
        return UUID.fromString(envelope.getAggregateId());
    }
}
