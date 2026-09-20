package com.sajidriaz.orderplatform.common.saga;

/**
 * Saga instance status (ADR-0003, REQUIREMENTS §4.6). Persisted on the saga_instance
 * row so an in-flight saga survives a crash. {@link #CONFIRMED} and {@link #CANCELLED}
 * are terminal; {@link #REQUIRES_RECONCILIATION} is a non-terminal holding state for an
 * unknown payment outcome (ADR-0016).
 */
public enum SagaStatus
{
    PENDING,
    STOCK_RESERVED,
    PAYMENT_AUTHORIZED,
    PAYMENT_CAPTURED,
    COMPENSATING,
    REQUIRES_RECONCILIATION,
    CONFIRMED,
    CANCELLED;

    public boolean isTerminal()
    {
        return this == CONFIRMED || this == CANCELLED;
    }
}
