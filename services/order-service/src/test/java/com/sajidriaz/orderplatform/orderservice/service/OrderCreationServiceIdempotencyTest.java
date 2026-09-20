package com.sajidriaz.orderplatform.orderservice.service;

import com.sajidriaz.orderplatform.orderservice.entity.IdempotencyKeyEntity;
import com.sajidriaz.orderplatform.orderservice.idempotency.RequestHasher;
import com.sajidriaz.orderplatform.orderservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.orderservice.pricing.PriceCatalog;
import com.sajidriaz.orderplatform.orderservice.repository.IdempotencyKeyRepository;
import com.sajidriaz.orderplatform.orderservice.repository.OrderRepository;
import com.sajidriaz.orderplatform.orderservice.repository.SagaInstanceRepository;
import com.sajidriaz.orderplatform.orderservice.saga.SagaOrchestrator;
import com.sajidriaz.orderplatform.orderservice.web.IdempotencyConflictException;
import com.sajidriaz.orderplatform.orderservice.web.dto.PlaceOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for edge (HTTP) idempotency conflict logic (ADR-0005 layer 1, S-5, S-18).
 * Pure Mockito — no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreationServiceIdempotencyTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private PriceCatalog priceCatalog;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private RequestHasher requestHasher;
    @Mock
    private OrderEventFactory orderEventFactory;
    @Mock
    private SagaOrchestrator sagaOrchestrator;

    private OrderCreationService service;

    private static final String CUSTOMER_ID = "customer-abc";
    private static final String METHOD = "POST";
    private static final String PATH = "/api/v1/orders";
    private static final String KEY = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        service = new OrderCreationService(orderRepository, sagaInstanceRepository, idempotencyKeyRepository,
                priceCatalog, outboxWriter, requestHasher, orderEventFactory, sagaOrchestrator);
    }

    @Test
    void sameKeySameHash_replaysOriginalResponse_withoutCreatingNewOrder() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity(
                KEY, CUSTOMER_ID, METHOD, PATH, "hash-1", 202,
                "{\"orderId\":\"x\",\"status\":\"PENDING\"}", java.util.UUID.randomUUID());
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.of(existing));

        PlaceOrderRequest request = new PlaceOrderRequest(
                List.of(new PlaceOrderRequest.OrderLineRequest("SKU-1001", 1)), "USD", "pi_test", null);

        OrderCreationService.Outcome outcome = service.placeOrder(CUSTOMER_ID, KEY, METHOD, PATH, request, "hash-1");

        assertThat(outcome).isInstanceOf(OrderCreationService.Replayed.class);
        var replayed = (OrderCreationService.Replayed) outcome;
        assertThat(replayed.status()).isEqualTo(202);
        assertThat(replayed.body()).isEqualTo(existing.getResponseBody());

        verify(orderRepository, never()).save(any());
    }

    @Test
    void sameKeyDifferentHash_throwsConflict_withoutCreatingNewOrder() {
        IdempotencyKeyEntity existing = new IdempotencyKeyEntity(
                KEY, CUSTOMER_ID, METHOD, PATH, "hash-1", 202,
                "{\"orderId\":\"x\",\"status\":\"PENDING\"}", java.util.UUID.randomUUID());
        when(idempotencyKeyRepository.findById(any())).thenReturn(Optional.of(existing));

        PlaceOrderRequest request = new PlaceOrderRequest(
                List.of(new PlaceOrderRequest.OrderLineRequest("SKU-1001", 5)), "USD", "pi_test", null);

        assertThatThrownBy(() -> service.placeOrder(CUSTOMER_ID, KEY, METHOD, PATH, request, "hash-2"))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(orderRepository, never()).save(any());
    }
}
