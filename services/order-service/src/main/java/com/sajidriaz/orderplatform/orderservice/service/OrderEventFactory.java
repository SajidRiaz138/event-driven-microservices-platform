package com.sajidriaz.orderplatform.orderservice.service;

import com.sajidriaz.orderplatform.common.Money;
import com.sajidriaz.orderplatform.events.order.CancellationReason;
import com.sajidriaz.orderplatform.events.order.OrderCancelled;
import com.sajidriaz.orderplatform.events.order.OrderConfirmed;
import com.sajidriaz.orderplatform.events.order.OrderCreated;
import com.sajidriaz.orderplatform.events.order.OrderLine;
import com.sajidriaz.orderplatform.orderservice.entity.OrderEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderLineEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds the Avro event payloads order-service owns and emits (ADR-0010 catalog):
 * {@code OrderCreated}, {@code OrderConfirmed}, {@code OrderCancelled}.
 */
@Component
public class OrderEventFactory {

    public OrderCreated toOrderCreated(OrderEntity order) {
        List<OrderLine> lines = order.getLines().stream()
                .map(this::toAvroLine)
                .toList();
        return OrderCreated.newBuilder()
                .setOrderId(order.getId())
                .setCustomerId(toUuidOrDerived(order.getCustomerId()))
                .setLines(lines)
                .setTotalAmount(toAvroMoney(order.getTotalMinorUnits(), order.getCurrency()))
                .setCreatedAt(order.getCreatedAt() == null ? Instant.now() : order.getCreatedAt())
                .build();
    }

    public OrderConfirmed toOrderConfirmed(UUID orderId, Instant confirmedAt) {
        return OrderConfirmed.newBuilder()
                .setOrderId(orderId)
                .setConfirmedAt(confirmedAt)
                .build();
    }

    public OrderCancelled toOrderCancelled(UUID orderId, CancellationReason reason, Instant cancelledAt) {
        return OrderCancelled.newBuilder()
                .setOrderId(orderId)
                .setReason(reason)
                .setCancelledAt(cancelledAt)
                .build();
    }

    private OrderLine toAvroLine(OrderLineEntity line) {
        return OrderLine.newBuilder()
                .setSku(line.getSku())
                .setQuantity(line.getQuantity())
                .setUnitPrice(toAvroMoney(line.getUnitPriceMinorUnits(), line.getCurrency()))
                .build();
    }

    private Money toAvroMoney(long minorUnits, String currency) {
        return Money.newBuilder()
                .setMinorUnits(minorUnits)
                .setCurrency(currency)
                .build();
    }

    /**
     * {@code OrderCreated.customerId} is typed as a UUID in the Avro schema, but the
     * dev-only caller-identity stand-in (X-User-Id, ADR-0009 TODO) may not be a UUID.
     * We derive a stable UUID (v3, name-based) so the event schema is honoured without
     * requiring every dev-mode caller id to already be a UUID; a real JWT `sub` will
     * commonly be a UUID that this collapses to itself only if literally parseable.
     */
    private UUID toUuidOrDerived(String customerId) {
        try {
            return UUID.fromString(customerId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(customerId.getBytes());
        }
    }
}
