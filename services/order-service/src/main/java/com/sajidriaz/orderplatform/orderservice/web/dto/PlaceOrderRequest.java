package com.sajidriaz.orderplatform.orderservice.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/orders} (shared/openapi/order-service.yaml
 * {@code PlaceOrderRequest}). Deliberately has NO {@code customerId} field — identity
 * comes from the caller's authentication (JWT {@code sub}, ADR-0009) — and NO price
 * fields; prices are resolved server-side (REST-API-GUIDE §1).
 */
public record PlaceOrderRequest(

        @NotEmpty(message = "at least one order line is required")
        @Size(max = 100, message = "at most 100 order lines are allowed")
        @Valid
        List<OrderLineRequest> lines,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO-4217 code")
        String currency,

        @NotBlank(message = "paymentInstrumentId is required")
        @Size(max = 128)
        String paymentInstrumentId,

        @Size(max = 128)
        String quoteId
) {

    public record OrderLineRequest(

            @NotBlank(message = "sku is required")
            @Size(min = 1, max = 64)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "sku has an invalid format")
            String sku,

            @jakarta.validation.constraints.Min(value = 1, message = "quantity must be >= 1")
            @jakarta.validation.constraints.Max(value = 100, message = "quantity must be <= 100")
            int quantity
    ) {
    }
}
