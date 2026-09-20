package com.sajidriaz.orderplatform.orderservice.saga;

import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReservationFailed;
import com.sajidriaz.orderplatform.orderservice.entity.ProcessedMessageEntity;
import com.sajidriaz.orderplatform.orderservice.messaging.EnvelopeCodec;
import com.sajidriaz.orderplatform.orderservice.messaging.Topics;
import com.sajidriaz.orderplatform.orderservice.repository.ProcessedMessageRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the saga reply consumer. Pure Mockito — no Spring, no broker.
 *
 * <p>The central case here is ADR-0006's rule that <em>business rejections are not DLQ
 * material</em>: an insufficient-stock or payment-declined reply is a normal saga outcome,
 * so it must be processed, dedup-recorded and acknowledged like any other message. Since
 * retry and dead-lettering in Spring Kafka are triggered by an exception escaping the
 * listener, "never retried, never dead-lettered" is asserted by showing that handling such
 * a reply throws nothing and acknowledges the offset.
 */
@ExtendWith(MockitoExtension.class)
class SagaReplyListenerTest {

    @Mock
    private SagaOrchestrator sagaOrchestrator;
    @Mock
    private ProcessedMessageRepository processedMessageRepository;
    @Mock
    private Acknowledgment acknowledgment;

    private final EnvelopeCodec envelopeCodec = new EnvelopeCodec();
    private SagaReplyListener listener;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        listener = new SagaReplyListener(sagaOrchestrator, processedMessageRepository, envelopeCodec);
        orderId = UUID.randomUUID();
    }

    @Test
    void businessRejection_isProcessedAndAcknowledged_notRetriedOrDeadLettered() {
        when(processedMessageRepository.existsById(any())).thenReturn(false);
        ConsumerRecord<String, byte[]> record = stockReservationFailedRecord();

        // Throwing here is what would hand the record to the error handler's retry/DLQ
        // ladder. A business rejection must never get there.
        assertThatCode(() -> listener.onReplyEvent(record, acknowledgment)).doesNotThrowAnyException();

        verify(sagaOrchestrator).onStockReservationFailed(eq(orderId), any());
        // Dedup row written for the reply, and the offset committed only after.
        verify(processedMessageRepository).save(any(ProcessedMessageEntity.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void redeliveredMessage_isSkipped_andNoTransitionIsReapplied() {
        when(processedMessageRepository.existsById(any())).thenReturn(true);
        ConsumerRecord<String, byte[]> record = stockReservationFailedRecord();

        listener.onReplyEvent(record, acknowledgment);

        // Already-processed message id: the effect must not be applied twice (S-10).
        verifyNoInteractions(sagaOrchestrator);
        verify(processedMessageRepository, never()).save(any());
        // Still acknowledged — a duplicate is successfully handled, not a failure.
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unmappedTopic_isAcknowledgedWithoutDispatch() {
        when(processedMessageRepository.existsById(any())).thenReturn(false);
        byte[] value = envelopeCodec.encodeEnvelope(MessageKind.EVENT, "events.payment.refunded",
                UUID.randomUUID(), null, orderId, StockReservationFailed.newBuilder()
                        .setOrderId(orderId)
                        .setFailedSku(null)
                        .setFailedAt(Instant.now())
                        .build());
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.EVENTS_PAYMENT_REFUNDED, 0, 0L, orderId.toString(), value);

        // No saga transition is mapped for this topic; the consumer logs and moves on
        // rather than throwing, which would pointlessly burn retries on a message no
        // amount of redelivery will make mappable.
        assertThatCode(() -> listener.onReplyEvent(record, acknowledgment)).doesNotThrowAnyException();

        verifyNoInteractions(sagaOrchestrator);
        verify(acknowledgment).acknowledge();
    }

    private ConsumerRecord<String, byte[]> stockReservationFailedRecord() {
        StockReservationFailed payload = StockReservationFailed.newBuilder()
                .setOrderId(orderId)
                .setFailedSku("SKU-1001")
                .setFailedAt(Instant.now())
                .build();
        byte[] value = envelopeCodec.encodeEnvelope(MessageKind.EVENT, "events.inventory.reservation-failed",
                UUID.randomUUID(), null, orderId, payload);
        return new ConsumerRecord<>(Topics.EVENTS_INVENTORY_RESERVATION_FAILED, 0, 0L, orderId.toString(), value);
    }
}
