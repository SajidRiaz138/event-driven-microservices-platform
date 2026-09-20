# Order Saga — Sequence Diagrams

Two flows through the order saga ([ADR-0003](../adr/0003-async-saga-orchestration.md)):
the happy path, and the payment-failure compensation path (the scenario the
one-command demo triggers on purpose). All inter-service communication is asynchronous
via Kafka; every producer writes through its **outbox** (ADR-0004); every consumer is
**idempotent** (ADR-0005). Envelope carries `correlationId` + `traceparent` end to end
(ADR-0010, ADR-0013).

## Happy path — order confirmed

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant GW as API Gateway
    participant O as order-service (saga)
    participant K as Kafka
    participant INV as inventory-service
    participant PAY as payment-service

    C->>GW: POST /orders (Idempotency-Key)
    GW->>O: forward (JWT validated)
    O->>O: persist Order=PENDING + saga_instance + outbox row (1 txn)
    O-->>C: 202 Accepted + status URL
    O->>K: OrderCreated (via outbox relay)

    O->>K: ReserveStock (command)
    K->>INV: ReserveStock
    INV->>INV: atomic conditional reserve (TTL) + outbox (1 txn)
    INV->>K: StockReserved
    K->>O: StockReserved

    O->>K: AuthorizePayment (command)
    K->>PAY: AuthorizePayment
    PAY->>PAY: authorize + outbox (1 txn)
    PAY->>K: PaymentAuthorized
    K->>O: PaymentAuthorized

    O->>K: CapturePayment (command)
    K->>PAY: CapturePayment
    PAY->>PAY: capture + outbox (1 txn)
    PAY->>K: PaymentCaptured
    Note over O,PAY: PIVOT — roll forward from here
    K->>O: PaymentCaptured

    O->>O: Order=CONFIRMED + outbox (1 txn)
    O->>K: OrderConfirmed
    C->>GW: GET /orders/{id}
    GW->>O: status
    O-->>C: CONFIRMED
```

## Compensation path — payment declined (pre-pivot)

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant GW as API Gateway
    participant O as order-service (saga)
    participant K as Kafka
    participant INV as inventory-service
    participant PAY as payment-service

    C->>GW: POST /orders (Idempotency-Key)
    GW->>O: forward (JWT validated)
    O->>O: persist Order=PENDING + saga + outbox (1 txn)
    O-->>C: 202 Accepted + status URL
    O->>K: OrderCreated

    O->>K: ReserveStock
    K->>INV: ReserveStock
    INV->>K: StockReserved
    K->>O: StockReserved

    O->>K: AuthorizePayment
    K->>PAY: AuthorizePayment
    PAY->>PAY: authorization FAILS (declined)
    PAY->>K: PaymentDeclined
    K->>O: PaymentDeclined

    Note over O: pre-pivot failure → COMPENSATE
    O->>K: ReleaseStock (compensation)
    K->>INV: ReleaseStock
    INV->>INV: release reservation + outbox (1 txn)
    INV->>K: StockReleased
    K->>O: StockReleased

    O->>O: Order=CANCELLED + outbox (1 txn)
    O->>K: OrderCancelled
    C->>GW: GET /orders/{id}
    GW->>O: status
    O-->>C: CANCELLED (reason: payment declined)
```

## Why these two

The happy path shows the full async orchestration with outbox at every hop. The
compensation path shows the saga *undoing* completed work (releasing the stock
reservation) when a later step fails — the core reason a saga exists. The demo script
forces the decline so a reviewer can watch compensation happen live, with one
`correlationId` tying the whole flow together in the traces.
