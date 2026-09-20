package com.sajidriaz.orderplatform.orderservice.web;

import com.sajidriaz.orderplatform.orderservice.idempotency.RequestHasher;
import com.sajidriaz.orderplatform.orderservice.security.CallerIdentityResolver;
import com.sajidriaz.orderplatform.orderservice.service.OrderCreationService;
import com.sajidriaz.orderplatform.orderservice.service.OrderQueryService;
import com.sajidriaz.orderplatform.orderservice.web.dto.OrderAcceptedResponse;
import com.sajidriaz.orderplatform.orderservice.web.dto.OrderResponse;
import com.sajidriaz.orderplatform.orderservice.web.dto.PlaceOrderRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Order placement and status (shared/openapi/order-service.yaml). Order placement is
 * asynchronous: valid requests return {@code 202 Accepted} immediately; the saga runs
 * behind the scenes (ADR-0003). See docs/api/REST-API-GUIDE.md.
 */
@RestController
@RequestMapping ("/api/v1/orders")
public class OrderController
{

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderCreationService orderCreationService;
    private final OrderQueryService orderQueryService;
    private final CallerIdentityResolver callerIdentityResolver;
    private final RequestHasher requestHasher;

    public OrderController(OrderCreationService orderCreationService,
                           OrderQueryService orderQueryService,
                           CallerIdentityResolver callerIdentityResolver,
                           RequestHasher requestHasher)
    {
        this.orderCreationService = orderCreationService;
        this.orderQueryService = orderQueryService;
        this.callerIdentityResolver = callerIdentityResolver;
        this.requestHasher = requestHasher;
    }

    @PostMapping
    public ResponseEntity<String> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                             @RequestHeader ("Idempotency-Key") String idempotencyKey,
                                             HttpServletRequest servletRequest)
    {
        String customerId = requireAuthenticatedCustomer();
        String path = servletRequest.getRequestURI();
        String requestHash = requestHasher.hash(request);

        OrderCreationService.Outcome outcome = orderCreationService.placeOrder(
                customerId, idempotencyKey, "POST", path, request, requestHash);

        return switch (outcome)
        {
            case OrderCreationService.Created created -> accepted(created.body());
            case OrderCreationService.Replayed replayed ->
                ResponseEntity.status(replayed.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(replayed.body());
        };
    }

    @GetMapping ("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId, HttpServletRequest servletRequest)
    {
        String customerId = requireAuthenticatedCustomer();
        OrderResponse response = orderQueryService.getOwnedOrder(orderId, customerId);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(response);
    }

    private ResponseEntity<String> accepted(OrderAcceptedResponse body)
    {
        URI location = URI.create("/api/v1/orders/" + body.orderId());
        String json = "{\"orderId\":\"" + body.orderId() + "\",\"status\":\"" + body.status() + "\"}";
        return ResponseEntity.accepted()
                .location(location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private String requireAuthenticatedCustomer()
    {
        String customerId = callerIdentityResolver.resolve();
        if (customerId == null)
        {
            throw new UnauthenticatedException("Missing caller identity.");
        }
        return customerId;
    }
}
