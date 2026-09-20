package com.sajidriaz.orderplatform.paymentservice.service;

import com.sajidriaz.orderplatform.common.money.Money;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationStatus;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationType;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentAttemptEntity;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentIntentEntity;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import com.sajidriaz.orderplatform.paymentservice.provider.PaymentProvider;
import com.sajidriaz.orderplatform.paymentservice.provider.ProviderResult;
import com.sajidriaz.orderplatform.paymentservice.provider.ProviderTimeoutException;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentAttemptRepository;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentIntentRepository;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment state machine of ADR-0016: authorize, capture exactly once, refund only against an
 * established capture, and never resolve an ambiguous outcome by guessing.
 *
 * <p>Three rules drive everything here:
 *
 * <ol>
 *   <li><strong>Deduplication is on the operation, never on the order.</strong> Each operation has a
 *       stable id and a {@code providerIdempotencyKey} reused across retries, so the provider
 *       collapses repeats. Keying on the order would forbid the legitimate second attempt after a
 *       declined card.</li>
 *   <li><strong>Capture happens at most once.</strong> An existing SUCCEEDED capture is reused
 *       rather than repeated; an UNKNOWN one is left to reconciliation rather than retried blindly.
 *       A unique index backs this so a race cannot defeat the check.</li>
 *   <li><strong>A lost response is UNKNOWN, not failure.</strong> Recording FAILED risks refunding a
 *       capture that never happened; recording SUCCEEDED confirms an unpaid order. Both are worse
 *       than admitting ignorance and reconciling (scenario S-17).</li>
 * </ol>
 *
 * <p>Provider calls happen inside the transaction that records their outcome. That is a deliberate
 * trade-off against ADR-0015's "never hold a connection across a network call": if the two were
 * split, a crash between them would lose the record of a call that already moved money. The stub
 * provider returns immediately; a real one would need this restructured around a PENDING record
 * committed before the call, which is exactly what the PENDING status exists to support.
 */
@Service
public class PaymentService
{

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentIntentRepository intentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentOperationRepository operationRepository;
    private final PaymentProvider provider;
    private final PaymentEventPublisher events;

    public PaymentService(PaymentIntentRepository intentRepository,
                          PaymentAttemptRepository attemptRepository,
                          PaymentOperationRepository operationRepository,
                          PaymentProvider provider,
                          PaymentEventPublisher events)
    {
        this.intentRepository = intentRepository;
        this.attemptRepository = attemptRepository;
        this.operationRepository = operationRepository;
        this.provider = provider;
        this.events = events;
    }

    /** What an AuthorizePayment command carries, after decoding. */
    public record AuthorizeCommand(UUID orderId, UUID customerId, UUID paymentIntentId,
            UUID paymentAttemptId, Money amount, String paymentMethodToken) {
    }

    /**
     * Authorize funds: place a hold. Replies {@code PaymentAuthorized} or {@code PaymentDeclined}.
     */
    @Transactional
    public void authorize(AuthorizeCommand command, UUID correlationId, UUID causationId)
    {
        PaymentIntentEntity intent = findOrCreateIntent(command);
        PaymentAttemptEntity attempt = findOrCreateAttempt(intent, command.paymentAttemptId(),
                command.paymentMethodToken());

        String idempotencyKey = authorizeKey(attempt.getId());
        Optional<PaymentOperationEntity> existing =
                operationRepository.findByProviderIdempotencyKey(idempotencyKey);
        if (existing.isPresent() && existing.get().getStatus().isResolved())
        {
            // Already answered. Re-emit the same reply: a repeated command usually means the
            // orchestrator never saw the first one.
            PaymentOperationEntity operation = existing.get();
            log.info("Authorization for order {} already {}; re-emitting reply",
                    command.orderId(), operation.getStatus());
            emitAuthorizeOutcome(operation, correlationId, causationId);
            return;
        }

        PaymentOperationEntity operation = existing.orElseGet(() -> operationRepository.save(
                new PaymentOperationEntity(UUID.randomUUID(), attempt, OperationType.AUTHORIZE,
                        idempotencyKey, intent.getAmount())));

        try
        {
            ProviderResult result = provider.authorize(new PaymentProvider.ProviderCall(
                    idempotencyKey, attempt.getPaymentMethodToken(), intent.getAmount()));
            if (result.approved())
            {
                operation.resolve(OperationStatus.SUCCEEDED, result.providerReference(), null);
                log.info("Authorized {} for order {} (operation {})",
                        intent.getAmount().minorUnits(), command.orderId(), operation.getId());
            }
            else
            {
                operation.resolve(OperationStatus.FAILED, null, result.failureReason());
                log.info("Authorization declined for order {}: {}",
                        command.orderId(), result.failureReason());
            }
            operationRepository.save(operation);
            emitAuthorizeOutcome(operation, correlationId, causationId);
        }
        catch (ProviderTimeoutException e)
        {
            // Even an authorization hold of unknown status must not be guessed at: a hold that
            // exists but is recorded as failed leaks the customer's available balance.
            operation.markUnknown(e.getProviderReference());
            operationRepository.save(operation);
            log.warn("Authorization outcome UNKNOWN for order {} (operation {}); left to "
                    + "reconciliation, no reply emitted yet", command.orderId(), operation.getId());
        }
    }

    /**
     * Capture previously authorized funds — the saga pivot. Replies {@code PaymentCaptured},
     * {@code PaymentCaptureFailed}, or {@code PaymentCaptureUnknown}.
     *
     * <p>Correlated by order id, not by the ids on the command: the orchestrator mints fresh
     * intent/attempt ids per command, so those do not match the authorization that preceded this.
     */
    @Transactional
    public void capture(UUID orderId, UUID correlationId, UUID causationId)
    {
        Optional<PaymentIntentEntity> maybeIntent = intentRepository.findByOrderId(orderId);
        if (maybeIntent.isEmpty())
        {
            log.error("CapturePayment for order {} but no payment intent exists; nothing was "
                    + "authorized here", orderId);
            return;
        }
        PaymentIntentEntity intent = maybeIntent.get();

        Optional<PaymentOperationEntity> authorization = latestSuccessfulAuthorization(intent);
        if (authorization.isEmpty())
        {
            log.warn("CapturePayment for order {} with no established authorization; replying "
                    + "capture-failed", orderId);
            PaymentAttemptEntity attempt = latestAttempt(intent);
            PaymentOperationEntity failed = operationRepository.save(
                    new PaymentOperationEntity(UUID.randomUUID(), attempt, OperationType.CAPTURE,
                            captureKey(attempt.getId()) + "-unauthorized-" + UUID.randomUUID(),
                            intent.getAmount()));
            failed.resolve(OperationStatus.FAILED, null, "not_authorized");
            operationRepository.save(failed);
            events.captureFailed(failed, correlationId, causationId);
            return;
        }

        PaymentAttemptEntity attempt = authorization.get().getAttempt();
        String idempotencyKey = captureKey(attempt.getId());
        Optional<PaymentOperationEntity> existingCapture =
                operationRepository.findByProviderIdempotencyKey(idempotencyKey);

        if (existingCapture.isPresent())
        {
            PaymentOperationEntity capture = existingCapture.get();
            switch (capture.getStatus())
            {
                case SUCCEEDED -> {
                    // Capture-once: the money is already taken. Re-emit the pivot event instead of
                    // calling the provider again.
                    log.info("Capture for order {} already SUCCEEDED (operation {}); re-emitting",
                            orderId, capture.getId());
                    events.captured(capture, correlationId, causationId);
                    return;
                }
                case UNKNOWN -> {
                    // Do not call capture again from here. The outcome is genuinely unknown and the
                    // reconciliation path exists precisely to establish it; a retry here would add
                    // nothing (the provider would collapse it on the same key) while risking a
                    // second reply event for one capture.
                    log.info("Capture for order {} is UNKNOWN (operation {}); awaiting reconciliation",
                            orderId, capture.getId());
                    events.captureUnknown(capture, correlationId, causationId);
                    return;
                }
                case FAILED -> {
                    log.info("Capture for order {} previously FAILED; replying capture-failed", orderId);
                    events.captureFailed(capture, correlationId, causationId);
                    return;
                }
                case PENDING -> performCapture(capture, attempt, intent, correlationId, causationId);
            }
            return;
        }

        PaymentOperationEntity capture = operationRepository.save(
                new PaymentOperationEntity(UUID.randomUUID(), attempt, OperationType.CAPTURE,
                        idempotencyKey, intent.getAmount()));
        performCapture(capture, attempt, intent, correlationId, causationId);
    }

    /** What a RefundPayment command carries, after decoding. */
    public record RefundCommand(UUID orderId, UUID paymentIntentId, UUID paymentOperationId,
            Money amount) {
    }

    /**
     * Refund a captured payment. Permitted only when the capture is positively SUCCEEDED
     * (ADR-0016 §2) — refunding against an UNKNOWN capture could return money that was never taken.
     */
    @Transactional
    public void refund(RefundCommand command, UUID correlationId, UUID causationId)
    {
        Optional<PaymentIntentEntity> maybeIntent = intentRepository.findByOrderId(command.orderId());
        if (maybeIntent.isEmpty())
        {
            log.error("RefundPayment for order {} but no payment intent exists", command.orderId());
            return;
        }
        PaymentIntentEntity intent = maybeIntent.get();

        Optional<PaymentOperationEntity> capture = successfulCapture(intent);
        if (capture.isEmpty())
        {
            // A business rejection, not a technical error: it must not throw, because throwing would
            // dead-letter a command that is simply not applicable.
            log.warn("Refusing to refund order {}: no capture is positively established as SUCCEEDED. "
                    + "Refunding an unknown or failed capture could return money never taken.",
                    command.orderId());
            return;
        }

        String idempotencyKey = refundKey(capture.get().getId());
        Optional<PaymentOperationEntity> existingRefund =
                operationRepository.findByProviderIdempotencyKey(idempotencyKey);
        if (existingRefund.isPresent() && existingRefund.get().getStatus() == OperationStatus.SUCCEEDED)
        {
            log.info("Refund for order {} already SUCCEEDED; re-emitting", command.orderId());
            events.refunded(existingRefund.get(), correlationId, causationId);
            return;
        }

        PaymentOperationEntity refund = existingRefund.orElseGet(() -> operationRepository.save(
                new PaymentOperationEntity(UUID.randomUUID(), capture.get().getAttempt(),
                        OperationType.REFUND, idempotencyKey, capture.get().getAmount())));

        try
        {
            ProviderResult result = provider.refund(new PaymentProvider.ProviderCall(
                    idempotencyKey, intent.getPaymentMethodToken(), refund.getAmount()));
            if (result.approved())
            {
                refund.resolve(OperationStatus.SUCCEEDED, result.providerReference(), null);
                operationRepository.save(refund);
                log.info("Refunded order {} (operation {})", command.orderId(), refund.getId());
                events.refunded(refund, correlationId, causationId);
            }
            else
            {
                refund.resolve(OperationStatus.FAILED, null, result.failureReason());
                operationRepository.save(refund);
                log.error("Refund for order {} was declined: {}. Requires operator attention.",
                        command.orderId(), result.failureReason());
            }
        }
        catch (ProviderTimeoutException e)
        {
            refund.markUnknown(e.getProviderReference());
            operationRepository.save(refund);
            log.warn("Refund outcome UNKNOWN for order {} (operation {}); left to reconciliation",
                    command.orderId(), refund.getId());
        }
    }

    /** All operations for an order, for the operator/reconciliation view. */
    @Transactional (readOnly = true)
    public List<PaymentOperationEntity> operationsForOrder(UUID orderId)
    {
        return operationRepository.findByOrderId(orderId);
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private void performCapture(PaymentOperationEntity capture,
                                PaymentAttemptEntity attempt,
                                PaymentIntentEntity intent,
                                UUID correlationId,
                                UUID causationId)
    {
        try
        {
            ProviderResult result = provider.capture(new PaymentProvider.ProviderCall(
                    capture.getProviderIdempotencyKey(), attempt.getPaymentMethodToken(),
                    intent.getAmount()));
            if (result.approved())
            {
                capture.resolve(OperationStatus.SUCCEEDED, result.providerReference(), null);
                operationRepository.save(capture);
                log.info("Captured {} for order {} (operation {}) — saga pivot",
                        intent.getAmount().minorUnits(), intent.getOrderId(), capture.getId());
                events.captured(capture, correlationId, causationId);
            }
            else
            {
                capture.resolve(OperationStatus.FAILED, null, result.failureReason());
                operationRepository.save(capture);
                log.info("Capture failed definitively for order {}: {}",
                        intent.getOrderId(), result.failureReason());
                events.captureFailed(capture, correlationId, causationId);
            }
        }
        catch (ProviderTimeoutException e)
        {
            // THE case this service exists for (ADR-0016 §2, scenario S-17). The funds may or may
            // not have moved. UNKNOWN is recorded and announced; only reconciliation may resolve it.
            capture.markUnknown(e.getProviderReference());
            operationRepository.save(capture);
            log.warn("Capture outcome UNKNOWN for order {} (operation {}, key {}): {}. "
                    + "Awaiting reconciliation; no second capture will be attempted.",
                    intent.getOrderId(), capture.getId(), capture.getProviderIdempotencyKey(),
                    e.getMessage());
            events.captureUnknown(capture, correlationId, causationId);
        }
    }

    private PaymentIntentEntity findOrCreateIntent(AuthorizeCommand command)
    {
        return intentRepository.findByOrderId(command.orderId())
                .orElseGet(() -> intentRepository.save(new PaymentIntentEntity(
                        // Adopt the orchestrator's intent id when creating, so both systems name the
                        // intent the same way. One intent per order (ADR-0016 §1).
                        command.paymentIntentId(), command.orderId(), command.customerId(),
                        command.amount(), command.paymentMethodToken())));
    }

    private PaymentAttemptEntity findOrCreateAttempt(PaymentIntentEntity intent,
                                                     UUID attemptId,
                                                     String paymentMethodToken)
    {
        return attemptRepository.findById(attemptId)
                .orElseGet(() -> attemptRepository.save(
                        new PaymentAttemptEntity(attemptId, intent, paymentMethodToken)));
    }

    private void emitAuthorizeOutcome(PaymentOperationEntity operation,
                                      UUID correlationId,
                                      UUID causationId)
    {
        if (operation.getStatus() == OperationStatus.SUCCEEDED)
        {
            events.authorized(operation, correlationId, causationId);
        }
        else if (operation.getStatus() == OperationStatus.FAILED)
        {
            events.declined(operation, correlationId, causationId);
        }
    }

    private Optional<PaymentOperationEntity> latestSuccessfulAuthorization(PaymentIntentEntity intent)
    {
        return attemptRepository.findByIntentIdOrderByCreatedAtAsc(intent.getId())
                .stream()
                .flatMap(attempt -> operationRepository.findByAttemptTypeAndStatus(
                        attempt.getId(), OperationType.AUTHORIZE, OperationStatus.SUCCEEDED).stream())
                .max(Comparator.comparing(PaymentOperationEntity::getCreatedAt));
    }

    private Optional<PaymentOperationEntity> successfulCapture(PaymentIntentEntity intent)
    {
        return attemptRepository.findByIntentIdOrderByCreatedAtAsc(intent.getId())
                .stream()
                .flatMap(attempt -> operationRepository.findByAttemptTypeAndStatus(
                        attempt.getId(), OperationType.CAPTURE, OperationStatus.SUCCEEDED).stream())
                .max(Comparator.comparing(PaymentOperationEntity::getCreatedAt));
    }

    private PaymentAttemptEntity latestAttempt(PaymentIntentEntity intent)
    {
        List<PaymentAttemptEntity> attempts =
                attemptRepository.findByIntentIdOrderByCreatedAtAsc(intent.getId());
        if (attempts.isEmpty())
        {
            return attemptRepository.save(new PaymentAttemptEntity(UUID.randomUUID(), intent,
                    intent.getPaymentMethodToken()));
        }
        return attempts.get(attempts.size() - 1);
    }

    // Idempotency keys are DERIVED, not random: the same logical operation must produce the same key
    // on every retry, or the provider could not collapse duplicates and capture-once would rely on
    // our bookkeeping alone.
    private String authorizeKey(UUID attemptId)
    {
        return "auth-" + attemptId;
    }

    private String captureKey(UUID attemptId)
    {
        return "cap-" + attemptId;
    }

    private String refundKey(UUID captureOperationId)
    {
        return "ref-" + captureOperationId;
    }
}
