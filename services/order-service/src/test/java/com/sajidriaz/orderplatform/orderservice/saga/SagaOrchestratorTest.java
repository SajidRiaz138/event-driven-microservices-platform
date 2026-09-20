package com.sajidriaz.orderplatform.orderservice.saga;

import com.sajidriaz.orderplatform.common.saga.SagaStatus;
import com.sajidriaz.orderplatform.orderservice.entity.CancellationReason;
import com.sajidriaz.orderplatform.orderservice.entity.OrderEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderLineEntity;
import com.sajidriaz.orderplatform.orderservice.entity.SagaInstanceEntity;
import com.sajidriaz.orderplatform.orderservice.entity.SagaStep;
import com.sajidriaz.orderplatform.orderservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.orderservice.repository.OrderRepository;
import com.sajidriaz.orderplatform.orderservice.repository.SagaInstanceRepository;
import com.sajidriaz.orderplatform.orderservice.service.OrderEventFactory;
import com.sajidriaz.orderplatform.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the saga state machine (docs/diagrams/saga-state-machine.md).
 * Pure Mockito — no Spring context, no containers (fast, per the parent pom's
 * surefire/failsafe split). Exercises: happy path, payment-declined compensation
 * (S-3), and idempotent duplicate reply handling (S-10, S-11).
 */
@ExtendWith (MockitoExtension.class)
class SagaOrchestratorTest
{

    private static final SagaTimeouts TEST_TIMEOUTS = new SagaTimeouts(900,
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    @Mock
    private OutboxWriter outboxWriter;

    private SagaOrchestrator orchestrator;

    private UUID orderId;
    private OrderEntity order;
    private SagaInstanceEntity saga;

    @BeforeEach
    void setUp()
    {
        orchestrator = new SagaOrchestrator(orderRepository, sagaInstanceRepository, outboxWriter,
                new OrderEventFactory(), TEST_TIMEOUTS);

        order = new OrderEntity(UUID.randomUUID().toString(), "USD", "pi_test");
        order.addLine(new OrderLineEntity("SKU-1001", 2, new Money(1999, "USD")));
        setOrderId(order, UUID.randomUUID());
        orderId = order.getId();

        saga = new SagaInstanceEntity(orderId, UUID.randomUUID());
        // In the real flow these ids are issued+persisted before STOCK_RESERVED /
        // PAYMENT_AUTHORIZED; the compensation and capture steps reuse them (see
        // V2 migration + SagaOrchestrator). Set them so fixtures match reality.
        saga.setReservationId(UUID.randomUUID());
        saga.setPaymentIntentId(UUID.randomUUID());
        saga.setPaymentAttemptId(UUID.randomUUID());
    }

    @Test
    void happyPath_reservedThenAuthorizedThenCaptured_confirmsOrder()
    {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStockReserved(orderId, UUID.randomUUID());
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.STOCK_RESERVED);

        orchestrator.onPaymentAuthorized(orderId, UUID.randomUUID());
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.PAYMENT_AUTHORIZED);

        orchestrator.onPaymentCaptured(orderId, UUID.randomUUID());
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(SagaStatus.CONFIRMED);

        // 3 commands (reserve happens via issueReserveStock in a separate flow) +
        // OrderConfirmed event went through the outbox, never a direct Kafka send.
        verify(outboxWriter, org.mockito.Mockito.atLeastOnce())
                .append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void paymentDeclinedAfterStockReserved_releasesStockAndCancelsWithReason()
    {
        saga.setStatus(SagaStatus.STOCK_RESERVED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onPaymentDeclined(orderId, UUID.randomUUID());

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);

        // Compensation must emit a ReleaseStock command AND the OrderCancelled event:
        // exactly 2 outbox appends.
        verify(outboxWriter, org.mockito.Mockito.times(2))
                .append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void insufficientStock_cancelsWithoutReleasingStock()
    {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStockReservationFailed(orderId, UUID.randomUUID());

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.INSUFFICIENT_STOCK);

        // No ReleaseStock is issued (nothing was ever reserved) — only the
        // OrderCancelled event: exactly 1 outbox append.
        verify(outboxWriter, org.mockito.Mockito.times(1))
                .append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateReplyAfterTerminal_isIgnored()
    {
        saga.setStatus(SagaStatus.CONFIRMED);
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        // A late/duplicate PaymentCaptured after the saga already confirmed must be a
        // no-op (S-11): status unchanged, no additional outbox writes.
        orchestrator.onPaymentCaptured(orderId, UUID.randomUUID());

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CONFIRMED);
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateReplyAfterCancelled_isIgnoredEvenIfPreviouslyStockReserved()
    {
        saga.setStatus(SagaStatus.CANCELLED);
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onPaymentAuthorized(orderId, UUID.randomUUID());

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void captureFailedAfterAuthorized_compensatesAndCancels()
    {
        saga.setStatus(SagaStatus.PAYMENT_AUTHORIZED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onPaymentCaptureFailed(orderId, UUID.randomUUID());

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_CAPTURE_FAILED);
    }

    // ---------------------------------------------------------------------
    // Compensation completion (events.inventory.released.v1)
    // ---------------------------------------------------------------------

    @Test
    void stockReleased_closesCompensationAndReachesDone()
    {
        saga.setStatus(SagaStatus.CANCELLED);
        saga.setCurrentStep(SagaStep.AWAITING_STOCK_RELEASE);
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStockReleased(orderId, UUID.randomUUID());

        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.DONE.name());
        assertThat(saga.getDeadline()).isNull();
        // The customer-visible outcome must not change: this only closes bookkeeping.
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stockReleased_isIgnoredWhenSagaWasNotAwaitingRelease()
    {
        saga.setStatus(SagaStatus.CANCELLED);
        saga.setCurrentStep(SagaStep.DONE);
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        // A duplicate release confirmation (or one for a saga that never reserved stock)
        // is a no-op rather than an error.
        orchestrator.onStockReleased(orderId, UUID.randomUUID());

        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.DONE.name());
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // Timeouts (ADR-0003)
    // ---------------------------------------------------------------------

    @Test
    void timeoutAwaitingStockReservation_cancelsWithOrderTimeout_withoutReleasingStock()
    {
        saga.setCurrentStep(SagaStep.AWAITING_STOCK_RESERVATION);
        saga.setDeadline(Instant.now().minusSeconds(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStepTimeout(orderId);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.ORDER_TIMEOUT);
        assertThat(saga.getAttemptCount()).isEqualTo(1);
        // Nothing was ever reserved, so only the OrderCancelled event is emitted.
        verify(outboxWriter, org.mockito.Mockito.times(1))
                .append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void timeoutAwaitingPaymentAuthorization_releasesStockAndCancelsWithOrderTimeout()
    {
        saga.setStatus(SagaStatus.STOCK_RESERVED);
        saga.setCurrentStep(SagaStep.AWAITING_PAYMENT_AUTHORIZATION);
        saga.setDeadline(Instant.now().minusSeconds(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStepTimeout(orderId);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.ORDER_TIMEOUT);
        // Stock WAS reserved, so compensation must release it: ReleaseStock + OrderCancelled.
        verify(outboxWriter, org.mockito.Mockito.times(2))
                .append(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The most important timeout case. ADR-0003: "a capture timeout or unknown provider
     * response is NOT success" — and it is not a failure either. Cancelling here could
     * cancel an order the provider actually charged, so the saga must stay non-terminal.
     */
    @Test
    void timeoutAtCaptureStep_neverCancels_andLeavesSagaNonTerminal()
    {
        saga.setStatus(SagaStatus.PAYMENT_AUTHORIZED);
        saga.setCurrentStep(SagaStep.AWAITING_PAYMENT_CAPTURE);
        saga.setDeadline(Instant.now().minusSeconds(1));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStepTimeout(orderId);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.PAYMENT_AUTHORIZED);
        assertThat(saga.getStatus().isTerminal()).isFalse();
        assertThat(order.getStatus()).isNotEqualTo(SagaStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isNull();
        // No compensation, no cancellation event — nothing may be emitted.
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
        // Swept once: the deadline is cleared so it is not re-reported every second.
        assertThat(saga.getDeadline()).isNull();
        assertThat(saga.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void timeoutSweep_ignoresSagaWhoseDeadlineWasAlreadyAdvanced()
    {
        // Another replica advanced the saga between the sweeper's SELECT and this call.
        saga.setStatus(SagaStatus.STOCK_RESERVED);
        saga.setCurrentStep(SagaStep.AWAITING_PAYMENT_AUTHORIZATION);
        saga.setDeadline(Instant.now().plusSeconds(60));
        when(sagaInstanceRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(saga));

        orchestrator.onStepTimeout(orderId);

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.STOCK_RESERVED);
        assertThat(saga.getAttemptCount()).isZero();
        verify(outboxWriter, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    // Reflection helper: OrderEntity's id is JPA-generated and has no public setter.
    private static void setOrderId(OrderEntity order, UUID id)
    {
        try
        {
            var field = OrderEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException(e);
        }
    }
}
