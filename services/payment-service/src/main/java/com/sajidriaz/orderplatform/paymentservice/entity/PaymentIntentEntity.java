package com.sajidriaz.orderplatform.paymentservice.entity;

import com.sajidriaz.orderplatform.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The intent to be paid for one order (ADR-0016 §1): one intent per order, carrying the amount and
 * the opaque instrument token.
 *
 * <p>The id is adopted from the orchestrator's {@code paymentIntentId} when the intent is first
 * created, so the two systems agree on an identifier. It is not used to correlate a later capture
 * back to this intent, because the orchestrator mints a fresh id per command — the order id is the
 * identifier that holds across the flow.
 */
@Entity
@Table (name = "payment_intent", schema = "payment")
public class PaymentIntentEntity
{

    @Id
    @Column (name = "id", nullable = false)
    private UUID id;

    @Column (name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column (name = "customer_id", nullable = false)
    private UUID customerId;

    @Column (name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column (name = "currency", nullable = false, length = 3)
    private String currency;

    /** Opaque provider token. Never a PAN or any card data (ADR-0009, ADR-0016 §4). */
    @Column (name = "payment_method_token", nullable = false, length = 255)
    private String paymentMethodToken;

    @Column (name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentIntentEntity()
    {
        // JPA
    }

    public PaymentIntentEntity(UUID id,
                               UUID orderId,
                               UUID customerId,
                               Money amount,
                               String paymentMethodToken)
    {
        this.id = id;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amountMinorUnits = amount.minorUnits();
        this.currency = amount.currency();
        this.paymentMethodToken = paymentMethodToken;
        this.createdAt = Instant.now();
    }

    public UUID getId()
    {
        return id;
    }

    public UUID getOrderId()
    {
        return orderId;
    }

    public UUID getCustomerId()
    {
        return customerId;
    }

    public Money getAmount()
    {
        return Money.of(amountMinorUnits, currency);
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
