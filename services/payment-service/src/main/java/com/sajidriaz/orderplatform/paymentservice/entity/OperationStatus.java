package com.sajidriaz.orderplatform.paymentservice.entity;

/**
 * Status of a single provider operation (ADR-0016 §2).
 *
 * <p>{@link #UNKNOWN} is the state this whole service is designed around. When a capture times out
 * or the response is lost, the provider may or may not have moved the money. Recording that as
 * {@code FAILED} invites a refund of a capture that never happened; recording it as
 * {@code SUCCEEDED} confirms an order nobody paid for. Both are worse than admitting ignorance, so
 * the outcome stays {@code UNKNOWN} until reconciliation establishes the truth by asking the
 * provider with the same idempotency key.
 */
public enum OperationStatus
{

    /** Recorded before calling the provider, so a crash mid-call leaves a trace to reconcile. */
    PENDING,

    /** Positively established as done. */
    SUCCEEDED,

    /** Positively established as not done. */
    FAILED,

    /** The provider's outcome is genuinely not known. Only reconciliation may resolve this. */
    UNKNOWN;

    /** True once the outcome is positively established either way. */
    public boolean isResolved()
    {
        return this == SUCCEEDED || this == FAILED;
    }

    /** True while the outcome is not yet established — PENDING or UNKNOWN. */
    public boolean isUnresolved()
    {
        return !isResolved();
    }
}
