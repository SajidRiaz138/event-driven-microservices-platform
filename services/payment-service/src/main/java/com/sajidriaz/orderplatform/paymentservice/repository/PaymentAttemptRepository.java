package com.sajidriaz.orderplatform.paymentservice.repository;

import com.sajidriaz.orderplatform.paymentservice.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    /** Attempts for an intent, oldest first. Several are legitimate (ADR-0016 §1). */
    List<PaymentAttemptEntity> findByIntentIdOrderByCreatedAtAsc(UUID intentId);
}
