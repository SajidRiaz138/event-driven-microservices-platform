package com.sajidriaz.orderplatform.orderservice.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/orders/{orderId}} (OpenAPI {@code Order}
 * schema). {@code status} is the PUBLIC view (PENDING|CONFIRMED|CANCELLED) — the
 * internal saga has finer states (incl. {@code REQUIRES_RECONCILIATION}) that are
 * deliberately not exposed here (REST-API-GUIDE §3). {@code reason} is non-null only
 * when {@code status == CANCELLED}.
 */
public record OrderResponse(
        UUID orderId,
        String status,
        String reason,
        List<OrderLineResponse> lines,
        MoneyResponse totalAmount,
        Instant createdAt,
        Instant updatedAt
) {

    public record OrderLineResponse(String sku, int quantity, MoneyResponse unitPrice, MoneyResponse lineTotal) {
    }

    public record MoneyResponse(long minorUnits, String currency) {
    }
}
