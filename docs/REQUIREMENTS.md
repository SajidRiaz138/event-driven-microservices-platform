# Requirements Specification — Event-Driven Order Platform

**Status:** Phase 1 (detailed) · Phase 2/3 (planned, stubbed)
**Related:** [ADRs](adr/README.md) · [NFR/SLO](NFR-and-SLO.md) · [Event Catalog](EVENT-CATALOG.md) · [API docs](api/REST-API-GUIDE.md)

This document states **what** the platform does in business terms, with exhaustive
positive **and** negative scenario coverage. Each acceptance criterion is expressed in
Given/When/Then form and is intended to become an automated test in the build phase.

---

## 1. Vision & scope

A customer places an order; the platform reserves stock, takes payment, and confirms
the order — coordinating three independently-owned services via an asynchronous saga,
keeping money and stock correct under failure.

**In scope (Phase 1):** api-gateway, auth-service, order-service, payment-service,
inventory-service; order placement, stock reservation, payment authorize/capture,
saga compensation, idempotent retries.

**Out of scope (Phase 1):** notifications (P2), audit store (P3), AI features (P3),
full multi-tenancy, shipping/fulfilment, returns beyond payment refund. See
[ROADMAP](ROADMAP.md).

## 2. Actors

| Actor | Description |
|---|---|
| **Customer** | Authenticated end user who places and tracks orders |
| **API Gateway** | Edge: routing, JWT validation, rate limiting, idempotency-key handling |
| **Saga Orchestrator** | Logic in order-service coordinating the distributed transaction |
| **Payment / Inventory services** | Autonomous saga participants |
| **Operator** | Runs the platform; consumes dashboards, DLQ alerts, runbooks |

## 3. Functional requirements

| ID | Requirement |
|---|---|
| **FR-1** | A customer can submit an order for one or more SKUs with quantities |
| **FR-2** | Every order submission must be authenticated with a valid JWT |
| **FR-3** | Order submission is idempotent per `Idempotency-Key` (scope: customer + endpoint + key). Same key + same payload → original result; same key + different payload → `409 Conflict`; in-progress → current order/status. order-service is authoritative (owns order state); the gateway may pre-enforce. Keys retained ≥ 24h. |
| **FR-4** | The system reserves stock before taking payment |
| **FR-5** | The system authorizes then captures payment (auth → capture), each as a distinct payment operation with a stable provider idempotency key (ADR-0016) |
| **FR-6** | On any pre-pivot failure, the system compensates (release stock) and cancels the order |
| **FR-7** | An order may transition to `CONFIRMED` **only after payment capture is durably established as succeeded**. A capture timeout or unknown provider response is **not** success — the order enters `REQUIRES_RECONCILIATION` until resolved (ADR-0016) |
| **FR-8** | A customer can query the current status of their own order |
| **FR-9** | A customer may only access their own orders (resource-level authorization) |
| **FR-10** | The system never oversells stock, even under concurrent orders — enforced by an **atomic conditional UPDATE** (`WHERE available >= :qty`) / row lock so available stock can never go negative; reservations have an id and a TTL, and release is idempotent (ADR-0015) |
| **FR-11** | The system never double-charges, even under retries/redelivery or lost provider responses — payment dedup is on payment-operation id / provider idempotency key, never on order_id (ADR-0016) |
| **FR-12** | Poison/unprocessable messages are quarantined (DLQ) and do not block the flow |
| **FR-13** | Every business flow is traceable end to end by a correlation id |
| **FR-14** | A capture with an unknown outcome (lost provider response) is resolved by **reconciliation** using the same provider idempotency key, capturing at most once (ADR-0016) |

## 4. Scenario catalog

Legend: ✅ positive · ❌ negative · ⚠️ edge/boundary.

### 4.1 Order placement

**✅ S-1 — Happy path**
> **Given** an authenticated customer and sufficient stock and a valid payment method
> **When** they submit a valid order with a fresh `Idempotency-Key`
> **Then** the API responds `202 Accepted` with a status URL, the order is `PENDING`,
> stock is reserved, payment is authorized then captured, and the order becomes `CONFIRMED`.

**❌ S-2 — Insufficient stock**
> **Given** requested quantity exceeds available stock
> **When** the order is submitted
> **Then** stock reservation fails, no payment is attempted, and the order becomes
> `CANCELLED` with reason `INSUFFICIENT_STOCK`.

**❌ S-3 — Payment declined (pre-pivot)**
> **Given** stock is reserved but the payment authorization is declined
> **When** the saga reaches the payment step
> **Then** the reserved stock is **released**, and the order becomes `CANCELLED` with
> reason `PAYMENT_DECLINED`.

**❌ S-4 — Capture fails after authorize**
> **Given** payment was authorized but capture fails
> **When** the capture step runs
> **Then** the saga retries capture within its budget; if still failing, it releases the
> authorization hold, releases stock, and cancels the order.

**❌ S-5 — Duplicate submission (idempotency)**
> **Given** an order was already submitted with `Idempotency-Key = K`
> **When** the identical request is submitted again with the same `K`
> **Then** the API returns the **same** result (same order id, same status) and **no**
> second order is created.

**⚠️ S-6 — Concurrent orders on last unit (no oversell)**
> **Given** exactly 1 unit of a SKU is available
> **When** two customers submit orders for that unit simultaneously
> **Then** exactly one reservation succeeds and the other fails with
> `INSUFFICIENT_STOCK` — stock never goes negative.

**⚠️ S-7 — Validation failures**
> **Given** a malformed order (empty lines, quantity ≤ 0, unknown SKU, currency
> mismatch, quantity above the per-order max)
> **When** it is submitted
> **Then** the API returns `400`/`422` with an RFC 9457 `problem+json` body describing
> the specific violation; nothing is persisted.

### 4.2 Reliability & messaging

**❌ S-8 — Transient downstream failure → retry → success**
> **Given** a participant is briefly unavailable (transient error)
> **When** it processes a command
> **Then** the message is retried with exponential backoff + jitter and succeeds within
> the retry budget; the saga completes normally (ADR-0014).

**❌ S-9 — Permanent failure → DLQ**
> **Given** a message is unprocessable (deserialization/business-rule/poison)
> **When** a consumer receives it
> **Then** it is routed **immediately** to the DLQ (no wasted retries), DLQ depth is
> alerted, and the main partition keeps flowing (ADR-0006).

**❌ S-10 — Redelivery (at-least-once) → no double effect**
> **Given** a consumer crashes after processing but before committing its offset
> **When** the message is redelivered on restart
> **Then** consumer dedup (`processed_message`) recognises the `eventId` and skips
> re-applying the effect (FR-11, ADR-0005).

**❌ S-11 — Late reply after saga timeout**
> **Given** a saga step timed out and compensated
> **When** the participant's (late) reply finally arrives
> **Then** it is treated as an idempotent no-op / triggers reconciliation, never
> mutating the already-terminal saga (ADR-0003).

**❌ S-12 — Producer crash mid-publish (outbox)**
> **Given** a service commits its business change and outbox row, then crashes before
> the relay publishes
> **When** it restarts
> **Then** the relay publishes the pending outbox row — the event is not lost (ADR-0004).

### 4.3 Security & authorization

**❌ S-13 — Missing/expired/invalid JWT**
> **Given** a request with no token, an expired token, or a bad signature
> **When** it hits any endpoint
> **Then** the response is `401 Unauthorized` — and every service validates
> independently, not just the gateway (ADR-0009).

**❌ S-14 — Insufficient scope**
> **Given** a valid token lacking the required scope
> **When** a protected action is attempted
> **Then** the response is `403 Forbidden`.

**❌ S-15 — Cross-customer access**
> **Given** customer A's valid token
> **When** A requests customer B's order
> **Then** the response is `404 Not Found` (not `403` — do not reveal existence) —
> resource-level ownership enforced in order-service (FR-9).

**⚠️ S-16 — Rate limit exceeded**
> **Given** a client exceeding its request quota
> **When** further requests arrive
> **Then** the response is `429 Too Many Requests` with `Retry-After`.

### 4.4 Payment correctness

**❌ S-17 — Capture response lost (unknown outcome)**
> **Given** the payment provider may have captured the payment but the response was lost
> **When** the payment service retries
> **Then** it reconciles using the **same** `providerIdempotencyKey` / operation id,
> creates **no** second charge, and treats the outcome as `UNKNOWN` until positively
> resolved; the order sits in `REQUIRES_RECONCILIATION` meanwhile (FR-11, FR-14, ADR-0016).

**❌ S-18 — Idempotency-key payload conflict**
> **Given** key `K` was used with request payload hash `H1`
> **When** the same key `K` is submitted with a different payload (hash `H2`)
> **Then** the API returns `409 Conflict` and creates **no** new order (FR-3).

## 4.5 Failure classification (retry vs DLQ vs reconciliation)

| Failure type | Action |
|---|---|
| Transient (network/DB/provider timeout, transient) | Retry with exponential backoff + jitter, bounded by attempts and saga deadline (ADR-0014) |
| Malformed payload / schema / deserialization | Immediate DLQ — no retries (ADR-0006) |
| Non-retryable business rejection (declined, insufficient stock) | Normal saga failure → compensate; not a DLQ case |
| Retry budget exhausted | DLQ + alert; saga step times out → compensate (ADR-0003, -0014) |
| Unknown external payment result | **Reconciliation** state (`REQUIRES_RECONCILIATION`), not ordinary retry (ADR-0016) |

Concrete policy (max 5 attempts, base 1s × 2, max 60s, full jitter, non-blocking retry
topics, poison→DLQ, DLQ depth alerted, one bad message never blocks a partition) is in
ADR-0014. A single poison message cannot block a partition because retries are
non-blocking (routed to retry topics) and permanent failures go straight to the DLQ.

## 4.6 Saga state machine (testable transitions)

```
PENDING
  ├─ STOCK_RESERVATION_FAILED ─────────────→ CANCELLED (INSUFFICIENT_STOCK)
  └─ STOCK_RESERVED
       ├─ PAYMENT_DECLINED ── COMPENSATING ─→ CANCELLED (PAYMENT_DECLINED)
       ├─ CAPTURE_FAILED ──── COMPENSATING ─→ CANCELLED
       ├─ CAPTURE_UNKNOWN ──────────────────→ REQUIRES_RECONCILIATION → {CONFIRMED | COMPENSATING→CANCELLED}
       └─ PAYMENT_CAPTURED (pivot) ─────────→ CONFIRMED
  └─ TIMEOUT ─────────────── COMPENSATING ─→ CANCELLED | REQUIRES_RECONCILIATION
```

Terminal states: `CONFIRMED`, `CANCELLED`. `REQUIRES_RECONCILIATION` is non-terminal.
Late events against terminal orders are **idempotent no-ops** or reconciliation signals
(S-11). Full diagram + compensation matrix: [diagrams/saga-state-machine.md](diagrams/saga-state-machine.md).

## 5. Non-functional requirements

All NFRs (latency, availability, throughput, ACID/isolation, concurrency/pooling,
durability, observability, security) are specified with concrete targets in
[NFR-and-SLO.md](NFR-and-SLO.md) and are part of the acceptance bar for Phase 1.

## 6. Data ownership rules (dev topology)

One PostgreSQL instance in dev, but strict logical isolation (ADR-0007):

- One **schema per service**, one **DB user per service** (grants limited to its schema).
- **No cross-schema queries**; **no foreign keys across service schemas**.
- **Migrations owned per service** (each service's Flyway scripts touch only its schema).
- Services integrate only via **APIs and events**, never by reading another's tables.
- Production may promote a schema to its own database instance **without changing
  service contracts** (connection config only).

## 7. Messaging

**Apache Kafka** is the Phase 1 broker (ADR-0017): partition key = `orderId`
(per-order ordering, cross-order parallelism), one consumer group per (service,
purpose), Avro + schema registry with BACKWARD compatibility, retry/DLT topology per
ADR-0006/0014.

## 8. Security boundaries

- Token **issuer/audience** fixed; **RS256/ES256** signing; **JWKS** with `kid`
  rotation (ADR-0009).
- `401` = missing/invalid/expired auth; `403` = authenticated but insufficient scope;
  `404` = resource hidden because owned by another customer (S-15).
- Public endpoints: `/auth/token`, health, metrics. All others require a valid JWT,
  validated **per service** (defense in depth).
- Service-to-service: `client_credentials` (autonomous) vs propagated user token /
  token exchange (on-behalf-of).

## 9. Mandatory Phase-1 test categories

The scenario catalog drives tests; the following categories are all required (the
crash-window tests matter as much as the happy path):

- Unit (domain logic) · API (endpoint contract) · consumer tests
- Database integration (Testcontainers Postgres) · Kafka integration (Testcontainers)
- End-to-end saga (happy + each failure branch)
- **Concurrent last-unit reservation** (no oversell, S-6)
- **Duplicate-event / redelivery** (S-10) · **outbox crash/restart** (S-12)
- **Consumer crash before offset commit** (S-10)
- **Payment timeout / reconciliation** (S-17)
- **DLQ and replay** (S-9) · contract-compatibility (schema registry)
- Security / resource-ownership (S-13, S-14, S-15)
- One-command **smoke test** (the demo scenario)

## 10. Traceability matrix

| FR | Scenarios | Services | Messages | ADRs |
|---|---|---|---|---|
| FR-1 | S-1, S-7 | gateway, order | OrderCreated | 0010 |
| FR-2, FR-13 | S-13 | gateway, all | envelope correlationId | 0009, 0010, 0013 |
| FR-3 | S-5, S-18 | gateway, order (authoritative) | Idempotency-Key | 0005 |
| FR-4, FR-10 | S-1, S-2, S-6 | inventory | ReserveStock / StockReserved / …Failed | 0007, 0015 |
| FR-5, FR-7, FR-14 | S-1, S-4, S-17 | payment, order (saga) | Authorize/CapturePayment, PaymentCaptured (pivot) | 0003, 0016 |
| FR-6 | S-2, S-3, S-4 | order (saga), payment, inventory | ReleaseStock, RefundPayment, OrderCancelled | 0003 |
| FR-8, FR-9 | S-1, S-15 | gateway, order | — (REST) | 0009 |
| FR-11 | S-10, S-17 | payment, all consumers | eventId, providerIdempotencyKey | 0005, 0014, 0016 |
| FR-12 | S-9 | all consumers | DLT topics | 0006, 0014 |

Every requirement maps to scenarios (→ tests), owning services, messages, and the
architectural decisions that justify the design. Phase 2/3 requirements
(notifications, audit, AI) will be appended when those phases begin.
