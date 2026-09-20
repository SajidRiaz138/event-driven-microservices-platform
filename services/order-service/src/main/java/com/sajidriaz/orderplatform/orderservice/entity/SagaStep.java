package com.sajidriaz.orderplatform.orderservice.entity;

/**
 * The step the saga orchestrator is currently awaiting a reply for
 * (saga-state-machine.md). Distinct from {@code SagaStatus}, which is the coarser
 * lifecycle status; {@code SagaStep} pins down exactly which reply event would
 * advance the saga next, for observability and timeout handling.
 */
public enum SagaStep {
    AWAITING_STOCK_RESERVATION,
    AWAITING_PAYMENT_AUTHORIZATION,
    AWAITING_PAYMENT_CAPTURE,
    AWAITING_STOCK_RELEASE,
    DONE
}
