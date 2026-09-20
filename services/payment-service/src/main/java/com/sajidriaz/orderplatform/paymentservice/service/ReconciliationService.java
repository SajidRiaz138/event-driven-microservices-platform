package com.sajidriaz.orderplatform.paymentservice.service;

import com.sajidriaz.orderplatform.paymentservice.entity.OperationStatus;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationType;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import com.sajidriaz.orderplatform.paymentservice.provider.PaymentProvider;
import com.sajidriaz.orderplatform.paymentservice.provider.ProviderResult;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Resolves operations whose outcome is UNKNOWN by asking the provider what actually happened
 * (ADR-0016 §2, scenario S-17).
 *
 * <p>This is the component that makes UNKNOWN a survivable state rather than a dead end. It asks
 * using the operation's original {@code providerIdempotencyKey}, so the question is "what happened
 * to this exact operation" — never a fresh attempt that could charge a second time.
 *
 * <p>Two rules it will not break:
 *
 * <ul>
 *   <li><strong>It never guesses.</strong> If the provider still cannot say, the operation stays
 *       UNKNOWN and the attempt is counted. Nothing about elapsed time converts ignorance into a
 *       fact.</li>
 *   <li><strong>It emits the same events as the direct path.</strong> A capture resolved here to
 *       SUCCEEDED publishes {@code PaymentCaptured} exactly as an immediate success would, so the
 *       saga can advance from a late resolution. The orchestrator treats a reply for a saga that has
 *       moved on as a no-op, so a late event is safe.</li>
 * </ul>
 *
 * <p>Claims rows with {@code FOR UPDATE SKIP LOCKED}: two replicas resolving the same operation
 * would publish the pivot event twice.
 */
@Service
public class ReconciliationService
{

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentOperationRepository operationRepository;
    private final PaymentProvider provider;
    private final PaymentEventPublisher events;
    private final int batchSize;
    private final int maxAttempts;

    public ReconciliationService(PaymentOperationRepository operationRepository,
                                 PaymentProvider provider,
                                 PaymentEventPublisher events,
                                 @Value ("${payment.reconciliation.batch-size:50}") int batchSize,
                                 @Value ("${payment.reconciliation.max-attempts:20}") int maxAttempts)
    {
        this.operationRepository = operationRepository;
        this.provider = provider;
        this.events = events;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled (fixedDelayString = "${payment.reconciliation.fixed-delay-ms:1000}")
    @Transactional
    public void reconcileUnknownOperations()
    {
        List<PaymentOperationEntity> unknown =
                operationRepository.lockUnknownOperations(batchSize, maxAttempts);
        if (unknown.isEmpty())
        {
            return;
        }
        log.info("Reconciling {} operation(s) with an UNKNOWN outcome", unknown.size());
        for (PaymentOperationEntity operation : unknown)
        {
            reconcile(operation);
        }
    }

    /**
     * Reconcile one operation. Package-private so tests can drive a single operation deterministically
     * rather than waiting for the scheduler.
     */
    @Transactional
    public void reconcile(PaymentOperationEntity operation)
    {
        if (operation.getStatus() != OperationStatus.UNKNOWN)
        {
            return;
        }

        Optional<ProviderResult> answer = provider.lookup(operation.getProviderIdempotencyKey());
        if (answer.isEmpty())
        {
            int attempts = operation.recordReconcileAttempt();
            operationRepository.save(operation);
            if (attempts >= maxAttempts)
            {
                // Deliberately still UNKNOWN. Out of reconciliation budget is a reason to escalate to
                // a human, not a licence to decide the outcome — money may be at stake either way.
                log.error("Operation {} ({} for order {}) is still UNKNOWN after {} reconciliation "
                        + "attempts and will no longer be polled automatically. Manual "
                        + "reconciliation required; the outcome remains undecided.",
                        operation.getId(), operation.getOperationType(),
                        operation.getAttempt().getIntent().getOrderId(), attempts);
            }
            else
            {
                log.info("Provider still cannot establish operation {} (attempt {}); staying UNKNOWN",
                        operation.getId(), attempts);
            }
            return;
        }

        ProviderResult result = answer.get();
        if (result.approved())
        {
            operation.resolve(OperationStatus.SUCCEEDED, result.providerReference(), null);
            operationRepository.save(operation);
            log.info("Reconciliation established operation {} ({}) as SUCCEEDED for order {}",
                    operation.getId(), operation.getOperationType(),
                    operation.getAttempt().getIntent().getOrderId());
            emitResolved(operation, true);
        }
        else
        {
            operation.resolve(OperationStatus.FAILED, null, result.failureReason());
            operationRepository.save(operation);
            log.info("Reconciliation established operation {} ({}) as FAILED for order {}: {}",
                    operation.getId(), operation.getOperationType(),
                    operation.getAttempt().getIntent().getOrderId(), result.failureReason());
            emitResolved(operation, false);
        }
    }

    /**
     * Publishes the reply the direct path would have published, had the response not been lost. The
     * correlation id is the operation's order id: no inbound message caused this, so there is no
     * causation id to carry.
     */
    private void emitResolved(PaymentOperationEntity operation, boolean succeeded)
    {
        if (operation.getOperationType() == OperationType.CAPTURE)
        {
            if (succeeded)
            {
                events.captured(operation, correlationIdFor(operation), null);
            }
            else
            {
                events.captureFailed(operation, correlationIdFor(operation), null);
            }
        }
        else if (operation.getOperationType() == OperationType.AUTHORIZE)
        {
            if (succeeded)
            {
                events.authorized(operation, correlationIdFor(operation), null);
            }
            else
            {
                events.declined(operation, correlationIdFor(operation), null);
            }
        }
        else if (operation.getOperationType() == OperationType.REFUND && succeeded)
        {
            events.refunded(operation, correlationIdFor(operation), null);
        }
    }

    /**
     * Reconciliation runs outside any message flow, so the original correlation id is not at hand.
     * The order id is used instead: it is stable, it is what every message of this flow is keyed by,
     * and it keeps the late event attached to the right order rather than to a fresh random id that
     * would join up with nothing.
     */
    private java.util.UUID correlationIdFor(PaymentOperationEntity operation)
    {
        return operation.getAttempt().getIntent().getOrderId();
    }
}
