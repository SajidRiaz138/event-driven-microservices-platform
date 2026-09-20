package com.sajidriaz.orderplatform.orderservice.entity;

import com.sajidriaz.orderplatform.common.saga.SagaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

// Note: `@Version` (optimistic locking) is a separate numeric column from the
// human-readable `updated_at` timestamp — Hibernate cannot manage a single column
// as both an optimistic-lock version and an @UpdateTimestamp.

/**
 * Persisted saga state (ADR-0003) — one row per order. The saga is never held only in
 * memory: {@code status} is the current {@link SagaStatus}, {@code currentStep} names
 * the command/reply being awaited, {@code deadline} drives timeout-triggered
 * retry/compensation (ADR-0014), and {@code attemptCount} bounds retries.
 */
@Entity
@Table(name = "saga_instance")
public class SagaInstanceEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private SagaStatus status;

    @Column(name = "current_step", length = 80)
    private String currentStep;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    /** The reservation id issued to inventory (ReserveStock), reused by ReleaseStock. */
    @Column(name = "reservation_id")
    private UUID reservationId;

    /** The payment intent id issued to payment (AuthorizePayment), reused by Capture/Refund. */
    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    /** The payment attempt id issued to payment (AuthorizePayment), reused by Capture. */
    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock; concurrent saga transitions on one order must not clobber each other. */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected SagaInstanceEntity() {
        // JPA
    }

    public SagaInstanceEntity(UUID orderId, UUID correlationId) {
        this.orderId = orderId;
        this.correlationId = correlationId;
        this.status = SagaStatus.PENDING;
        this.currentStep = SagaStep.AWAITING_STOCK_RESERVATION.name();
        this.attemptCount = 0;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public void setStatus(SagaStatus status) {
        this.status = status;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(SagaStep step) {
        this.currentStep = step.name();
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void incrementAttemptCount() {
        this.attemptCount++;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void setReservationId(UUID reservationId) {
        this.reservationId = reservationId;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public void setPaymentIntentId(UUID paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public void setPaymentAttemptId(UUID paymentAttemptId) {
        this.paymentAttemptId = paymentAttemptId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
