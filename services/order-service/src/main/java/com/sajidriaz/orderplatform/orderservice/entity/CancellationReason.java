package com.sajidriaz.orderplatform.orderservice.entity;

/**
 * Mirrors the Avro {@code CancellationReason} enum (events/OrderCancelled.avsc) as a
 * JPA-persisted value so order-service can store it without a hard dependency on the
 * generated Avro class in the entity layer.
 */
public enum CancellationReason {
    INSUFFICIENT_STOCK,
    PAYMENT_DECLINED,
    PAYMENT_CAPTURE_FAILED,
    ORDER_TIMEOUT,
    RECONCILIATION_REQUIRED
}
