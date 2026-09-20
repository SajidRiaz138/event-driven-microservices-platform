package com.sajidriaz.orderplatform.paymentservice.entity;

import com.sajidriaz.orderplatform.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One call to the payment provider (ADR-0016 §1): an authorize, capture, refund or void, with our
 * own stable {@code operationId} (the primary key) and the {@code providerIdempotencyKey} sent to
 * the provider.
 *
 * <p>The key rule, enforced in {@link #resolve} and {@link #markUnknown}: an operation may leave
 * {@code UNKNOWN} only by being positively established as SUCCEEDED or FAILED. Nothing may guess.
 * The same {@code providerIdempotencyKey} is reused for every retry of this operation, which is
 * what makes a double charge structurally impossible rather than merely unlikely — the provider
 * collapses our repeated calls into one effect.
 */
@Entity
@Table (name = "payment_operation", schema = "payment")
public class PaymentOperationEntity
{

    @Id
    @Column (name = "id", nullable = false)
    private UUID id;

    @ManyToOne (fetch = FetchType.EAGER)
    @JoinColumn (name = "attempt_id", nullable = false)
    private PaymentAttemptEntity attempt;

    @Enumerated (EnumType.STRING)
    @Column (name = "operation_type", nullable = false, length = 16)
    private OperationType operationType;

    @Enumerated (EnumType.STRING)
    @Column (name = "status", nullable = false, length = 16)
    private OperationStatus status;

    @Column (name = "provider_idempotency_key", nullable = false, length = 255, updatable = false)
    private String providerIdempotencyKey;

    @Column (name = "provider_reference", length = 255)
    private String providerReference;

    @Column (name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column (name = "currency", nullable = false, length = 3)
    private String currency;

    @Column (name = "failure_reason", length = 255)
    private String failureReason;

    @Column (name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column (name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column (name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column (name = "lock_version", nullable = false)
    private long lockVersion;

    protected PaymentOperationEntity()
    {
        // JPA
    }

    public PaymentOperationEntity(UUID id,
                                  PaymentAttemptEntity attempt,
                                  OperationType operationType,
                                  String providerIdempotencyKey,
                                  Money amount)
    {
        this.id = id;
        this.attempt = attempt;
        this.operationType = operationType;
        this.providerIdempotencyKey = providerIdempotencyKey;
        this.amountMinorUnits = amount.minorUnits();
        this.currency = amount.currency();
        // PENDING before the provider is called, so a crash mid-call leaves something to reconcile
        // rather than no record that the call was ever made.
        this.status = OperationStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId()
    {
        return id;
    }

    public PaymentAttemptEntity getAttempt()
    {
        return attempt;
    }

    public OperationType getOperationType()
    {
        return operationType;
    }

    public OperationStatus getStatus()
    {
        return status;
    }

    public String getProviderIdempotencyKey()
    {
        return providerIdempotencyKey;
    }

    public String getProviderReference()
    {
        return providerReference;
    }

    public Money getAmount()
    {
        return Money.of(amountMinorUnits, currency);
    }

    public String getFailureReason()
    {
        return failureReason;
    }

    public int getReconcileAttempts()
    {
        return reconcileAttempts;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }

    public long getLockVersion()
    {
        return lockVersion;
    }

    /**
     * Positively establish the outcome. Refuses to change an already-resolved operation: a capture
     * that is SUCCEEDED must never silently become FAILED (or the reverse) on a later message,
     * because money has already moved and the earlier fact is the true one.
     */
    public void resolve(OperationStatus resolution, String providerReference, String failureReason)
    {
        if (!resolution.isResolved())
        {
            throw new IllegalArgumentException(resolution + " is not a resolved outcome");
        }
        if (this.status.isResolved() && this.status != resolution)
        {
            throw new IllegalStateException("operation " + id + " is already " + this.status
                    + " and cannot become " + resolution);
        }
        this.status = resolution;
        if (providerReference != null)
        {
            this.providerReference = providerReference;
        }
        this.failureReason = failureReason;
        this.updatedAt = Instant.now();
    }

    /**
     * The provider's outcome is not known — a timeout or lost response (ADR-0016 §2, scenario
     * S-17). Refuses to overwrite an established outcome: once the truth is known, losing it to a
     * later ambiguous attempt would be a regression into ignorance.
     */
    public void markUnknown(String providerReference)
    {
        if (this.status.isResolved())
        {
            throw new IllegalStateException("operation " + id + " is already " + this.status
                    + " and must not regress to UNKNOWN");
        }
        this.status = OperationStatus.UNKNOWN;
        if (providerReference != null)
        {
            this.providerReference = providerReference;
        }
        this.updatedAt = Instant.now();
    }

    /** Count a reconciliation query that still could not establish the outcome. */
    public int recordReconcileAttempt()
    {
        this.updatedAt = Instant.now();
        return ++this.reconcileAttempts;
    }
}
