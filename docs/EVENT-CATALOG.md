# Event & Command Catalog

The canonical inventory of the platform's asynchronous messages. This catalog is the
integration contract between services — see [ADR-0010](adr/0010-event-envelope-and-topic-taxonomy.md)
for the envelope and taxonomy rules, and [ADR-0003](adr/0003-async-saga-orchestration.md)
for how these messages drive the order saga.

## Commands vs Events — the distinction

| | **Command** | **Event** |
|---|---|---|
| Intent | "Do this" (imperative) | "This happened" (fact, past tense) |
| Recipients | Exactly one owning handler | Any number of interested consumers |
| May be rejected? | Yes | No — a fact cannot be rejected |
| Topic prefix | `commands.<target>.<action>.v<major>` | `events.<owner>.<fact>.v<major>` |
| Emitted by | The saga orchestrator (order-service) | The service that owns the fact |

## Envelope (every message)

All messages wrap their payload in the standard envelope (`Envelope.avsc`):

| Field | Type | Purpose |
|---|---|---|
| `messageId` | uuid (string) | Unique id; idempotency/dedup key for consumers (ADR-0005). Used for commands and events alike |
| `messageKind` | enum | `COMMAND` (imperative, one handler, may be rejected) vs `EVENT` (fact, fan-out) |
| `correlationId` | uuid (string) | One business flow (one order) across all services |
| `causationId` | uuid (string) | The message that directly caused this one |
| `occurredAt` | timestamp-millis | When the fact happened / command was issued (UTC) |
| `type` | string | Fully-qualified message type, e.g. `events.order.created` |
| `schemaVersion` | int | Payload schema major version |
| `tenantId` | string | Tenant scope; `"default"` for now (ADR-0011) |
| `aggregateId` | string | Aggregate id; **Kafka partition key** (per-aggregate ordering) |
| `traceparent` | string (nullable) | W3C trace context for end-to-end tracing (ADR-0013) |
| `payload` | bytes/union | The command- or event-specific body |

## Phase 1 catalog — the order saga

The order → payment → inventory saga (orchestrated by order-service).

### Events (facts)

| Event | Topic | Owner | Emitted when | Key consumers |
|---|---|---|---|---|
| `OrderCreated` | `events.order.created.v1` | order | Order accepted (status PENDING) | saga orchestrator, audit |
| `OrderConfirmed` | `events.order.confirmed.v1` | order | Saga completed successfully | notification (P2), audit |
| `OrderCancelled` | `events.order.cancelled.v1` | order | Saga failed & compensated | notification (P2), audit |
| `PaymentAuthorized` | `events.payment.authorized.v1` | payment | Funds authorized (pre-capture) | saga orchestrator, audit |
| `PaymentCaptured` | `events.payment.captured.v1` | payment | Funds captured (**pivot**) | saga orchestrator, audit |
| `PaymentDeclined` | `events.payment.declined.v1` | payment | Authorization/capture failed | saga orchestrator, audit |
| `PaymentRefunded` | `events.payment.refunded.v1` | payment | Compensation after capture | saga orchestrator, audit |
| `StockReserved` | `events.inventory.reserved.v1` | inventory | Reservation created (TTL) | saga orchestrator, audit |
| `StockReservationFailed` | `events.inventory.reservation-failed.v1` | inventory | Insufficient available stock | saga orchestrator, audit |
| `StockReleased` | `events.inventory.released.v1` | inventory | Reservation released (compensation/expiry) | saga orchestrator, audit |

### Commands (imperatives, issued by the orchestrator)

| Command | Topic | Target | Effect |
|---|---|---|---|
| `AuthorizePayment` | `commands.payment.authorize.v1` | payment | Authorize funds for the order |
| `CapturePayment` | `commands.payment.capture.v1` | payment | Capture previously authorized funds (**pivot**) |
| `RefundPayment` | `commands.payment.refund.v1` | payment | Compensate a captured payment |
| `ReserveStock` | `commands.inventory.reserve.v1` | inventory | Reserve stock for the order (with TTL) |
| `ReleaseStock` | `commands.inventory.release.v1` | inventory | Release a reservation (compensation) |

### Dead-letter topics

Per ADR-0006: `<topic>.<consumer-group>.DLT` for messages exhausting retries or
classified permanent.

## Saga flow (summary)

```
OrderCreated
  → ReserveStock ──▶ StockReserved ──▶ AuthorizePayment ──▶ PaymentAuthorized
                                                              │
                                              CapturePayment  ▼   ◀── PIVOT
                                                          PaymentCaptured
                                                              │
                                                        OrderConfirmed
Failure before pivot → compensate (ReleaseStock, no capture) → OrderCancelled
Failure at/after pivot → roll forward (retry capture) OR RefundPayment + ReleaseStock
Late reply after timeout → idempotent terminal state / reconciliation (ADR-0003, -0005)
```

Full state machine and the step-by-failure matrix: `diagrams/saga-state-machine.md`.

## Avro schemas & dual use (Kafka + Cassandra audit)

- Schemas live in [`../shared/avro-schemas/`](../shared/avro-schemas/) and are the
  **single source of truth** for these message payloads.
- They serve Kafka serialization (registered in the schema registry, BACKWARD compat
  enforced in CI) **and** are the canonical record persisted by the audit-service
  (Cassandra, Phase 3). An audited event and its Kafka event share one schema — no
  drift between the wire format and the stored record. Cassandra tables are designed
  query-first around these event types, but the payload contract is the Avro schema.
