package com.sajidriaz.orderplatform.orderservice.service;

import com.sajidriaz.orderplatform.common.saga.SagaStatus;
import com.sajidriaz.orderplatform.orderservice.entity.OrderEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderLineEntity;
import com.sajidriaz.orderplatform.orderservice.repository.OrderRepository;
import com.sajidriaz.orderplatform.orderservice.web.OrderNotFoundException;
import com.sajidriaz.orderplatform.orderservice.web.dto.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Order status lookup with resource-level ownership enforcement (FR-9, S-15):
 * requesting another customer's order returns 404, never revealing existence
 * ({@link OrderNotFoundException} is thrown identically for "missing" and
 * "not owned").
 */
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOwnedOrder(UUID orderId, String callerCustomerId) {
        OrderEntity order = orderRepository.findById(orderId)
                .filter(o -> o.isOwnedBy(callerCustomerId))
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        return toResponse(order);
    }

    private OrderResponse toResponse(OrderEntity order) {
        String publicStatus = toPublicStatus(order.getStatus());
        String reason = order.getStatus() == SagaStatus.CANCELLED && order.getCancellationReason() != null
                ? order.getCancellationReason().name()
                : null;

        var lines = order.getLines().stream()
                .map(this::toLineResponse)
                .toList();

        return new OrderResponse(
                order.getId(),
                publicStatus,
                reason,
                lines,
                new OrderResponse.MoneyResponse(order.getTotalMinorUnits(), order.getCurrency()),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private OrderResponse.OrderLineResponse toLineResponse(OrderLineEntity line) {
        return new OrderResponse.OrderLineResponse(
                line.getSku(),
                line.getQuantity(),
                new OrderResponse.MoneyResponse(line.getUnitPriceMinorUnits(), line.getCurrency()),
                new OrderResponse.MoneyResponse(line.getLineTotalMinorUnits(), line.getCurrency()));
    }

    /**
     * Projects the internal {@link SagaStatus} (which includes
     * {@code REQUIRES_RECONCILIATION} and intermediate states) down to the public
     * {@code PENDING|CONFIRMED|CANCELLED} view (REST-API-GUIDE §3): "PENDING includes
     * normal processing AND payment reconciliation".
     */
    private String toPublicStatus(SagaStatus status) {
        return switch (status) {
            case CONFIRMED -> "CONFIRMED";
            case CANCELLED -> "CANCELLED";
            default -> "PENDING";
        };
    }
}
