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
     * {@code OrderCreated.customerId} is typed as a UUID in the Avro schema. A Keycloak
     * {@code sub} is a UUID, so the normal path is a straight parse.
     *
     * <p>The name-based (v3) fallback is kept for identities that are well-formed but not
     * UUIDs — another identity provider, or a test subject. It is deterministic, so the same
     * caller always maps to the same event customerId; deriving a random UUID here would make
     * one customer look like many to every downstream consumer. (Before ADR-0009 was
     * implemented, identity came from a dev-only header whose value was arbitrary, which is
     * why this fallback exists at all.)
     */
    private UUID toUuidOrDerived(String customerId) {
        try {
            return UUID.fromString(customerId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(customerId.getBytes());
        }
    }
}
