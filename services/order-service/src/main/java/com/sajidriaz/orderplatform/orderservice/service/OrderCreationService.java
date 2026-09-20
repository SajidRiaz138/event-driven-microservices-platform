package com.sajidriaz.orderplatform.orderservice.service;

import com.sajidriaz.orderplatform.common.money.Money;
import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.orderservice.entity.IdempotencyKeyEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderEntity;
import com.sajidriaz.orderplatform.orderservice.entity.OrderLineEntity;
import com.sajidriaz.orderplatform.orderservice.entity.SagaInstanceEntity;
import com.sajidriaz.orderplatform.orderservice.idempotency.RequestHasher;
import com.sajidriaz.orderplatform.orderservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.orderservice.messaging.Topics;
import com.sajidriaz.orderplatform.orderservice.pricing.PriceCatalog;
import com.sajidriaz.orderplatform.orderservice.repository.IdempotencyKeyRepository;
import com.sajidriaz.orderplatform.orderservice.repository.OrderRepository;
import com.sajidriaz.orderplatform.orderservice.repository.SagaInstanceRepository;
import com.sajidriaz.orderplatform.orderservice.saga.SagaOrchestrator;
import com.sajidriaz.orderplatform.orderservice.web.IdempotencyConflictException;
import com.sajidriaz.orderplatform.orderservice.web.OrderValidationException;
import com.sajidriaz.orderplatform.orderservice.web.dto.OrderAcceptedResponse;
import com.sajidriaz.orderplatform.orderservice.web.dto.PlaceOrderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Order creation use case (ADR-0003, ADR-0004, ADR-0005): in ONE transaction,
 * resolves server-side prices, persists the order (status PENDING), the initial
 * {@code saga_instance}, an {@code OrderCreated} outbox record, and the idempotency
 * record — then returns immediately. Read-your-writes: the order row is committed
 * before the caller's {@code 202} response (REST-API-GUIDE §1 "Asynchronous operations").
 */
@Service
public class OrderCreationService
{

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PriceCatalog priceCatalog;
    private final OutboxWriter outboxWriter;
    private final RequestHasher requestHasher;
    private final OrderEventFactory orderEventFactory;
    private final SagaOrchestrator sagaOrchestrator;

    public OrderCreationService(OrderRepository orderRepository,
                                SagaInstanceRepository sagaInstanceRepository,
                                IdempotencyKeyRepository idempotencyKeyRepository,
                                PriceCatalog priceCatalog,
                                OutboxWriter outboxWriter,
                                RequestHasher requestHasher,
                                OrderEventFactory orderEventFactory,
                                SagaOrchestrator sagaOrchestrator)
    {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.priceCatalog = priceCatalog;
        this.outboxWriter = outboxWriter;
        this.requestHasher = requestHasher;
        this.orderEventFactory = orderEventFactory;
        this.sagaOrchestrator = sagaOrchestrator;
    }

    /**
     * Outcome of a place-order call: whether a new order was created, or an existing
     * idempotent response should be replayed.
     */
    public sealed interface Outcome permits Created, Replayed
    {
    }

    public record Created(OrderAcceptedResponse body) implements Outcome {
    }

    public record Replayed(int status, String body) implements Outcome {
    }

    @Transactional
    public Outcome placeOrder(String customerId,
                              String idempotencyKey,
                              String method,
                              String path,
                              PlaceOrderRequest request,
                              String requestBodyHash)
    {
        Optional<IdempotencyKeyEntity> existing = idempotencyKeyRepository.findById(
                new IdempotencyKeyEntity.Key(idempotencyKey, customerId, method, path));

        if (existing.isPresent())
        {
            IdempotencyKeyEntity record = existing.get();
            if (!record.getRequestHash().equals(requestBodyHash))
            {
                throw new IdempotencyConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request body.");
            }
            return new Replayed(record.getResponseStatus(), record.getResponseBody());
        }

        OrderEntity order = new OrderEntity(customerId, request.currency(), request.paymentInstrumentId());
        for (PlaceOrderRequest.OrderLineRequest lineRequest : request.lines())
        {
            Money unitPrice = priceCatalog.unitPriceFor(lineRequest.sku(), request.currency())
                    .orElseThrow(() -> new OrderValidationException(
                            "Unknown SKU or unsupported currency for line: " + lineRequest.sku()));
            order.addLine(new OrderLineEntity(lineRequest.sku(), lineRequest.quantity(), unitPrice));
        }

        OrderEntity savedOrder = orderRepository.save(order);

        // The correlationId of the whole saga is the one the caller's request already
        // carries (ADR-0013: "one correlationId stitches together the logs of an entire
        // business flow"). CorrelationIdFilter has put it in the MDC and returned it in
        // the X-Correlation-Id response header, so the id the client is handed is the id
        // that travels on every Envelope to payment and inventory. Minting a fresh one
        // here would hand the client an identifier that appears in no other service's
        // logs.
        UUID correlationId = correlationIdForSaga();
        SagaInstanceEntity saga = new SagaInstanceEntity(savedOrder.getId(), correlationId);
        sagaInstanceRepository.save(saga);

        outboxWriter.append(
                MessageKind.EVENT,
                "events.order.created",
                Topics.EVENTS_ORDER_CREATED,
                correlationId,
                null,
                savedOrder.getId(),
                orderEventFactory.toOrderCreated(savedOrder));

        // Kick off the saga: issue ReserveStock in the SAME transaction as order
        // creation. Kept synchronous-in-transaction (rather than triggered by
        // consuming the just-outboxed OrderCreated event) for Phase 1 simplicity —
        // the causal chain (order created -> reserve stock requested) is still fully
        // captured in the outbox and saga_instance.current_step.
        sagaOrchestrator.issueReserveStock(savedOrder, correlationId);

        OrderAcceptedResponse response = OrderAcceptedResponse.pending(savedOrder.getId());

        idempotencyKeyRepository.save(new IdempotencyKeyEntity(
                idempotencyKey, customerId, method, path, requestBodyHash,
                202, toJson(response), savedOrder.getId()));

        return new Created(response);
    }

    /**
     * The ambient correlation id, as a UUID, or a fresh one.
     *
     * <p>Two fallbacks, both real. There may be no MDC value at all — this method is
     * reachable from a call that did not come through {@link
     * com.sajidriaz.orderplatform.orderservice.web.CorrelationIdFilter} (a test, or any
     * future non-HTTP trigger). And the value need not be a UUID: the filter adopts an
     * inbound {@code X-Correlation-Id} verbatim, while {@code saga_instance.correlation_id}
     * and the Avro envelope's {@code correlationId} are typed as UUIDs. A caller sending
     * a non-UUID header must not turn a valid order into a 500.
     */
    private static UUID correlationIdForSaga()
    {
        String current = CorrelationContext.currentCorrelationId();
        if (current == null || current.isBlank())
        {
            return UUID.randomUUID();
        }
        try
        {
            return UUID.fromString(current);
        }
        catch (IllegalArgumentException notAUuid)
        {
            return UUID.randomUUID();
        }
    }

    private String toJson(OrderAcceptedResponse response)
    {
        return "{\"orderId\":\"" + response.orderId() + "\",\"status\":\"" + response.status() + "\"}";
    }
}
