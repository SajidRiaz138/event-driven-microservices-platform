package com.sajidriaz.orderplatform.paymentservice.repository;

import com.sajidriaz.orderplatform.paymentservice.entity.OperationStatus;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationType;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentOperationRepository extends JpaRepository<PaymentOperationEntity, UUID>
{

    /**
     * Dedup lookup by the provider idempotency key (ADR-0016 §1). This, and the operation id, are
     * the only legitimate deduplication keys — never the order id, which would forbid the legitimate
     * second attempt after a declined card.
     */
    Optional<PaymentOperationEntity> findByProviderIdempotencyKey(String providerIdempotencyKey);

    List<PaymentOperationEntity> findByAttemptIdAndOperationType(UUID attemptId, OperationType type);

    /** All operations for an order, newest last — the operator/reconciliation view. */
    @Query ("""
            select o from PaymentOperationEntity o
            where o.attempt.intent.orderId = :orderId
            order by o.createdAt
            """)
    List<PaymentOperationEntity> findByOrderId(@Param ("orderId") UUID orderId);

    /** The operations of one attempt with a given type and status. */
    @Query ("""
            select o from PaymentOperationEntity o
            where o.attempt.id = :attemptId
              and o.operationType = :type
              and o.status = :status
            """)
    List<PaymentOperationEntity> findByAttemptTypeAndStatus(@Param ("attemptId") UUID attemptId,
                                                            @Param ("type") OperationType type,
                                                            @Param ("status") OperationStatus status);

    /**
     * Claims a batch of UNKNOWN operations for reconciliation. {@code FOR UPDATE SKIP LOCKED} lets
     * several replicas reconcile concurrently without two of them resolving the same operation — and
     * resolving an operation twice would emit the pivot event twice.
     *
     * <p>Ordered oldest-first so the longest-unresolved money is chased first.
     */
    @Query (value = """
            select * from payment.payment_operation
            where status = 'UNKNOWN'
              and reconcile_attempts < :maxAttempts
            order by updated_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<PaymentOperationEntity> lockUnknownOperations(@Param ("batchSize") int batchSize,
                                                       @Param ("maxAttempts") int maxAttempts);

    /** Operations stuck UNKNOWN beyond the reconciliation budget — an alerting signal. */
    long countByStatus(OperationStatus status);
}
