package com.sajidriaz.orderplatform.paymentservice.repository;

import com.sajidriaz.orderplatform.paymentservice.entity.PaymentIntentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntentEntity, UUID> {

    /**
     * The intent for an order.
     *
     * <p>This is how a capture is correlated back to its authorization. The ids on the incoming
     * commands cannot be used for that: the orchestrator mints a fresh {@code paymentIntentId} and
     * {@code paymentAttemptId} for every command it sends, so the ids on a CapturePayment do not
     * match those on the AuthorizePayment that preceded it. The order id — carried as the envelope's
     * {@code aggregateId} — is the only identifier stable across the flow, and "one intent per
     * order" (ADR-0016 §1) makes it sufficient.
     */
    Optional<PaymentIntentEntity> findByOrderId(UUID orderId);
}
