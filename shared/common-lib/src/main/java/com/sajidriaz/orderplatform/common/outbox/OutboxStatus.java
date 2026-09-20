package com.sajidriaz.orderplatform.common.outbox;

/**
 * Lifecycle of a transactional outbox row (ADR-0004). Written in the same DB transaction
 * as the business change; a relay drains {@link #PENDING} rows to Kafka using
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} and marks them {@link #SENT}.
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
