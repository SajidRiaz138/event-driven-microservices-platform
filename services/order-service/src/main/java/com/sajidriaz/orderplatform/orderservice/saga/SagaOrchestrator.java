package com.sajidriaz.orderplatform.orderservice.saga;

import com.sajidriaz.orderplatform.commands.inventory.ReleaseStock;
import com.sajidriaz.orderplatform.commands.inventory.ReserveLine;
import com.sajidriaz.orderplatform.commands.inventory.ReserveStock;
import com.sajidriaz.orderplatform.commands.payment.AuthorizePayment;
import com.sajidriaz.orderplatform.commands.payment.CapturePayment;
import com.sajidriaz.orderplatform.common.Money;
import com.sajidriaz.orderplatform.common.saga.SagaStatus;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.orderservice.entity.CancellationReason;
import com.sajidriaz.orderplatform.orderservice.entity.OrderEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderLineEntity;
import com.sajidriaz.orderplatform.orderservice.entity.SagaInstanceEntity;
import com.sajidriaz.orderplatform.orderservice.entity.SagaStep;
import com.sajidriaz.orderplatform.orderservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.orderservice.messaging.Topics;
import com.sajidriaz.orderplatform.orderservice.repository.OrderRepository;
import com.sajidriaz.orderplatform.orderservice.repository.SagaInstanceRepository;
import com.sajidriaz.orderplatform.orderservice.service.OrderEventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * The order saga's persisted state machine (ADR-0003, docs/diagrams/saga-state-machine.md).
 *
 * <p>Every transition method here is invoked from within a {@code @Transactional}
 * caller (the Kafka listener, see {@code SagaReplyListener}) that has already
 * inserted the {@code processed_message} dedup row for the inbound reply — so a
 * transition and its dedup marker commit atomically (ADR-0005 layer 2). Emitted
 * commands/events go through the {@link OutboxWriter} in the SAME transaction
 * (ADR-0004), never a direct Kafka send.
 *
 * <p>Terminal states ({@code CONFIRMED}, {@code CANCELLED}) are idempotent no-ops for
 * any further transition attempt — a late reply after compensation must never mutate
 * an already-terminal saga (S-11).
 */
@Service
public class SagaOrchestrator
{

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OutboxWriter outboxWriter;
    private final OrderEventFactory orderEventFactory;
    private final SagaTimeouts timeouts;

    public SagaOrchestrator(OrderRepository orderRepository,
                            SagaInstanceRepository sagaInstanceRepository,
                            OutboxWriter outboxWriter,
                            OrderEventFactory orderEventFactory,
                            SagaTimeouts timeouts)
    {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.outboxWriter = outboxWriter;
        this.orderEventFactory = orderEventFactory;
        this.timeouts = timeouts;
    }

    // ---------------------------------------------------------------------
    // Step 1: OrderCreated -> issue ReserveStock
    // ---------------------------------------------------------------------

    /**
     * Issues the {@code ReserveStock} command for a newly-created order. Called by the
     * outbox relay path is NOT appropriate here — this is invoked directly after order
     * creation commits (see {@code OrderCreationService}) OR, in this saga's design,
     * as the first action taken once the {@code OrderCreated} event itself is
     * confirmed persisted. For Phase 1 simplicity and to keep the causal chain
     * explicit, order-service issues {@code ReserveStock} synchronously within the
     * same transaction that creates the order and its {@code OrderCreated} outbox row.
     */
    @Transactional
    public void issueReserveStock(OrderEntity order, UUID correlationId)
    {
        UUID reservationId = UUID.randomUUID();
        List<ReserveLine> lines = order.getLines()
                .stream()
                .map(l -> ReserveLine.newBuilder().setSku(l.getSku()).setQuantity(l.getQuantity()).build())
                .toList();

        ReserveStock command = ReserveStock.newBuilder()
                .setOrderId(order.getId())
                .setReservationId(reservationId)
                .setLines(lines)
                .setTtlSeconds(timeouts.reservationTtlSeconds())
                .build();

        outboxWriter.append(MessageKind.COMMAND, "commands.inventory.reserve",
                Topics.COMMANDS_INVENTORY_RESERVE, correlationId, null, order.getId(), command);

        SagaInstanceEntity saga = requireSaga(order.getId());
        saga.setReservationId(reservationId);
        saga.setCurrentStep(SagaStep.AWAITING_STOCK_RESERVATION);
        saga.setDeadline(Instant.now().plus(timeouts.awaitStockReservation()));
    }

    // ---------------------------------------------------------------------
    // Step 2: StockReserved -> issue AuthorizePayment | StockReservationFailed -> cancel
    // ---------------------------------------------------------------------

    @Transactional
    public void onStockReserved(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late StockReserved for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.PENDING)
        {
            log.info("Ignoring StockReserved for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }

        saga.setStatus(SagaStatus.STOCK_RESERVED);
        OrderEntity order = requireOrder(orderId);

        UUID paymentIntentId = UUID.randomUUID();
        UUID paymentAttemptId = UUID.randomUUID();
        saga.setPaymentIntentId(paymentIntentId);
        saga.setPaymentAttemptId(paymentAttemptId);

        AuthorizePayment command = AuthorizePayment.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(toUuidOrDerived(order.getCustomerId()))
                .setPaymentIntentId(paymentIntentId)
                .setPaymentAttemptId(paymentAttemptId)
                .setAmount(toAvroMoney(order))
                // TODO(ADR-0009 / payment integration): resolve the opaque
                // paymentInstrumentId to a real provider token server-side once
                // payment-service exists. For Phase 1 the reference is passed through.
                .setPaymentMethodToken(order.getPaymentInstrumentId())
                .build();

        outboxWriter.append(MessageKind.COMMAND, "commands.payment.authorize",
                Topics.COMMANDS_PAYMENT_AUTHORIZE, saga.getCorrelationId(), causationId, orderId, command);

        saga.setCurrentStep(SagaStep.AWAITING_PAYMENT_AUTHORIZATION);
        saga.setDeadline(Instant.now().plus(timeouts.awaitPaymentAuthorization()));
    }

    @Transactional
    public void onStockReservationFailed(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late StockReservationFailed for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.PENDING)
        {
            log.info("Ignoring StockReservationFailed for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }
        // Nothing to compensate: stock was never reserved (REQUIREMENTS S-2).
        cancelOrder(orderId, saga, CancellationReason.INSUFFICIENT_STOCK, causationId, false);
    }

    // ---------------------------------------------------------------------
    // Step 3: PaymentAuthorized -> issue CapturePayment | PaymentDeclined -> compensate
    // ---------------------------------------------------------------------

    @Transactional
    public void onPaymentAuthorized(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late PaymentAuthorized for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.STOCK_RESERVED)
        {
            log.info("Ignoring PaymentAuthorized for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }

        saga.setStatus(SagaStatus.PAYMENT_AUTHORIZED);
        OrderEntity order = requireOrder(orderId);

        CapturePayment command = CapturePayment.newBuilder()
                .setOrderId(orderId)
                .setPaymentIntentId(saga.getPaymentIntentId())
                .setPaymentAttemptId(saga.getPaymentAttemptId())
                .build();

        outboxWriter.append(MessageKind.COMMAND, "commands.payment.capture",
                Topics.COMMANDS_PAYMENT_CAPTURE, saga.getCorrelationId(), causationId, orderId, command);

        saga.setCurrentStep(SagaStep.AWAITING_PAYMENT_CAPTURE);
        saga.setDeadline(Instant.now().plus(timeouts.awaitPaymentCapture()));
    }

    @Transactional
    public void onPaymentDeclined(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late PaymentDeclined for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.STOCK_RESERVED && saga.getStatus() != SagaStatus.PAYMENT_AUTHORIZED)
        {
            log.info("Ignoring PaymentDeclined for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }
        // Pre-pivot failure: release the stock reservation, then cancel (FR-6, S-3).
        cancelOrder(orderId, saga, CancellationReason.PAYMENT_DECLINED, causationId, true);
    }

    // ---------------------------------------------------------------------
    // Step 4 (PIVOT): PaymentCaptured -> confirm | PaymentCaptureFailed -> compensate
    // ---------------------------------------------------------------------

    @Transactional
    public void onPaymentCaptured(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late PaymentCaptured for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.PAYMENT_AUTHORIZED)
        {
            log.info("Ignoring PaymentCaptured for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }

        // PIVOT (ADR-0003): after this, the saga rolls FORWARD, never compensates.
        saga.setStatus(SagaStatus.PAYMENT_CAPTURED);
        saga.setCurrentStep(SagaStep.DONE);
        saga.setDeadline(null);

        OrderEntity order = requireOrder(orderId);
        order.setStatus(SagaStatus.CONFIRMED);

        Instant confirmedAt = Instant.now();
        outboxWriter.append(MessageKind.EVENT, "events.order.confirmed", Topics.EVENTS_ORDER_CONFIRMED,
                saga.getCorrelationId(), causationId, orderId, orderEventFactory.toOrderConfirmed(orderId, confirmedAt));

        saga.setStatus(SagaStatus.CONFIRMED);
    }

    @Transactional
    public void onPaymentCaptureFailed(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            log.info("Ignoring late PaymentCaptureFailed for terminal order {}", orderId);
            return;
        }
        if (saga.getStatus() != SagaStatus.PAYMENT_AUTHORIZED)
        {
            log.info("Ignoring PaymentCaptureFailed for order {} in unexpected state {}", orderId, saga.getStatus());
            return;
        }
        // Still pre-pivot (capture never succeeded): release stock, cancel (S-4).
        cancelOrder(orderId, saga, CancellationReason.PAYMENT_CAPTURE_FAILED, causationId, true);
    }

    // ---------------------------------------------------------------------
    // Compensation reply: StockReleased -> compensation complete
    // ---------------------------------------------------------------------

    /**
     * Closes the compensation loop. {@code ReleaseStock} is fired while cancelling, which
     * leaves the saga at step {@code AWAITING_STOCK_RELEASE}; without consuming
     * inventory's {@code StockReleased} confirmation that step would stay stuck forever,
     * so every compensated saga would look permanently unfinished to dashboards and any
     * reconciliation job reading {@code current_step} (ADR-0003's compensation path,
     * {@code events.inventory.released.v1} in ADR-0010's catalog).
     *
     * <p>Deliberately NOT guarded by {@code isTerminal()} like the forward transitions:
     * the order status is already {@code CANCELLED} by this point, and that is correct
     * and must not change. This advances only the internal step bookkeeping, never the
     * customer-visible status. Idempotent: a duplicate or unexpected confirmation is a
     * no-op.
     */
    @Transactional
    public void onStockReleased(UUID orderId, UUID causationId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (!SagaStep.AWAITING_STOCK_RELEASE.name().equals(saga.getCurrentStep()))
        {
            log.info("Ignoring StockReleased (causation {}) for order {}: not awaiting release (step={})",
                    causationId, orderId, saga.getCurrentStep());
            return;
        }
        saga.setCurrentStep(SagaStep.DONE);
        saga.setDeadline(null);
        log.info("Compensation complete for order {}: stock reservation released", orderId);
    }

    // ---------------------------------------------------------------------
    // Timeouts (ADR-0003: timer-driven against the persisted deadline)
    // ---------------------------------------------------------------------

    /**
     * Applies the timeout policy for a saga whose persisted {@code deadline} has passed
     * with no reply. Invoked by {@link SagaTimeoutSweeper}.
     *
     * <p><strong>Pre-pivot</strong> (awaiting stock reservation or payment
     * authorization): nothing irreversible has happened, so the saga compensates and
     * cancels with reason {@code ORDER_TIMEOUT} — releasing stock only if it was
     * actually reserved.
     *
     * <p><strong>At the capture step</strong>: the saga MUST NOT cancel. ADR-0003 is
     * explicit that "a capture timeout or unknown provider response is NOT success" and
     * equally not a failure — forcing either a confirm or a cancel here risks cancelling
     * an order the provider actually charged. The correct destination is the
     * {@code REQUIRES_RECONCILIATION} holding state, which cannot be reached honestly
     * yet: resolving a true UNKNOWN requires payment-service's reconciliation loop
     * (ADR-0016) and a capture-outcome-unknown signal that has no Avro schema in
     * {@code shared/avro-schemas} today. So this logs at ERROR for alerting and leaves
     * the saga untouched and non-terminal, which is the safe half of the documented
     * behavior. Deferred to the payment-service task.
     *
     * <p>The deadline is cleared once swept so a stuck saga is reported once rather than
     * on every sweep.
     */
    @Transactional
    public void onStepTimeout(UUID orderId)
    {
        SagaInstanceEntity saga = requireSagaForUpdate(orderId);
        if (saga.getStatus().isTerminal())
        {
            saga.setDeadline(null);
            return;
        }
        Instant deadline = saga.getDeadline();
        if (deadline == null || deadline.isAfter(Instant.now()))
        {
            // Re-checked under the row lock: another replica may have advanced the saga
            // between the sweeper's SELECT and this transition.
            return;
        }

        saga.incrementAttemptCount();
        String step = saga.getCurrentStep();

        if (SagaStep.AWAITING_PAYMENT_CAPTURE.name().equals(step))
        {
            saga.setDeadline(null);
            log.error("Capture-step timeout for order {} after {} attempt(s): the payment outcome is UNKNOWN, "
                    + "so the saga is left non-terminal rather than confirmed or cancelled (ADR-0003). "
                    + "Full REQUIRES_RECONCILIATION handling is deferred to payment-service (ADR-0016).",
                    orderId, saga.getAttemptCount());
            return;
        }

        if (SagaStep.AWAITING_STOCK_RESERVATION.name().equals(step))
        {
            log.warn("Timeout awaiting stock reservation for order {}; cancelling (nothing to release)", orderId);
            cancelOrder(orderId, saga, CancellationReason.ORDER_TIMEOUT, null, false);
        }
        else if (SagaStep.AWAITING_PAYMENT_AUTHORIZATION.name().equals(step))
        {
            log.warn("Timeout awaiting payment authorization for order {}; releasing stock and cancelling", orderId);
            cancelOrder(orderId, saga, CancellationReason.ORDER_TIMEOUT, null, true);
        }
        else
        {
            log.warn("Deadline passed for order {} at step {}; no timeout policy applies", orderId, step);
            saga.setDeadline(null);
        }
    }

    // ---------------------------------------------------------------------
    // Compensation
    // ---------------------------------------------------------------------

    /** After compensation (ReleaseStock) completes, or immediately if nothing to release. */
    private void cancelOrder(UUID orderId,
                             SagaInstanceEntity saga,
                             CancellationReason reason,
                             UUID causationId,
                             boolean releaseStock)
    {
        saga.setStatus(SagaStatus.COMPENSATING);

        if (releaseStock)
        {
            ReleaseStock command = ReleaseStock.newBuilder()
                    .setOrderId(orderId)
                    .setReservationId(saga.getReservationId())
                    .build();
            outboxWriter.append(MessageKind.COMMAND, "commands.inventory.release",
                    Topics.COMMANDS_INVENTORY_RELEASE, saga.getCorrelationId(), causationId, orderId, command);
            saga.setCurrentStep(SagaStep.AWAITING_STOCK_RELEASE);
        }
        else
        {
            saga.setCurrentStep(SagaStep.DONE);
        }
        saga.setDeadline(null);

        OrderEntity order = requireOrder(orderId);
        order.setStatus(SagaStatus.CANCELLED);
        order.setCancellationReason(reason);

        com.sajidriaz.orderplatform.events.order.CancellationReason avroReason =
                com.sajidriaz.orderplatform.events.order.CancellationReason.valueOf(reason.name());

        outboxWriter.append(MessageKind.EVENT, "events.order.cancelled", Topics.EVENTS_ORDER_CANCELLED,
                saga.getCorrelationId(), causationId, orderId,
                orderEventFactory.toOrderCancelled(orderId, avroReason, Instant.now()));

        saga.setStatus(SagaStatus.CANCELLED);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private SagaInstanceEntity requireSaga(UUID orderId)
    {
        return sagaInstanceRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("No saga_instance for order " + orderId));
    }

    private SagaInstanceEntity requireSagaForUpdate(UUID orderId)
    {
        return sagaInstanceRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NoSuchElementException("No saga_instance for order " + orderId));
    }

    private OrderEntity requireOrder(UUID orderId)
    {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("No order " + orderId));
    }

    private Money toAvroMoney(OrderEntity order)
    {
        return Money.newBuilder()
                .setMinorUnits(order.getTotalMinorUnits())
                .setCurrency(order.getCurrency())
                .build();
    }

    private UUID toUuidOrDerived(String customerId)
    {
        try
        {
            return UUID.fromString(customerId);
        }
        catch (IllegalArgumentException e)
        {
            return UUID.nameUUIDFromBytes(customerId.getBytes());
        }
    }
}
