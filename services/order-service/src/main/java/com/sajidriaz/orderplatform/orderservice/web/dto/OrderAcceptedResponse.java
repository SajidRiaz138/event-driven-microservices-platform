package com.sajidriaz.orderplatform.orderservice.web.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/orders} (202 Accepted). Matches the OpenAPI
 * {@code OrderAccepted} schema exactly: only {@code orderId} and the literal status
 * {@code PENDING}.
 */
public record OrderAcceptedResponse(UUID orderId, String status)
{
    public static OrderAcceptedResponse pending(UUID orderId)
    {
        return new OrderAcceptedResponse(orderId, "PENDING");
    }
}
