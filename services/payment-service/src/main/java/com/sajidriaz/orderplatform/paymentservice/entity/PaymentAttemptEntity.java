package com.sajidriaz.orderplatform.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One try at paying an intent (ADR-0016 §1) — one card, one authorization, one capture.
 *
 * <p>Several attempts per intent are legitimate: a declined card retried with another instrument,
 * or a split payment. Representing that is the reason deduplication must not live at the order
 * level, which is the modelling error ADR-0016 exists to correct.
 *
 * <p>Deliberately carries no status column. The attempt's state is fully determined by its
 * operations, and a duplicated status would be a second source of truth free to disagree with them.
 */
@Entity
@Table (name = "payment_attempt", schema = "payment")
public class PaymentAttemptEntity
{

    @Id
    @Column (name = "id", nullable = false)
    private UUID id;

    @ManyToOne (fetch = FetchType.EAGER)
    @JoinColumn (name = "intent_id", nullable = false)
    private PaymentIntentEntity intent;

    /** Opaque provider token used for this attempt. Never a PAN. */
    @Column (name = "payment_method_token", nullable = false, length = 255)
    private String paymentMethodToken;

    @Column (name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttemptEntity()
    {
        // JPA
    }

    public PaymentAttemptEntity(UUID id, PaymentIntentEntity intent, String paymentMethodToken)
    {
        this.id = id;
        this.intent = intent;
        this.paymentMethodToken = paymentMethodToken;
        this.createdAt = Instant.now();
    }

    public UUID getId()
    {
        return id;
    }

    public PaymentIntentEntity getIntent()
    {
        return intent;
    }

    public String getPaymentMethodToken()
    {
        return paymentMethodToken;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }
}
