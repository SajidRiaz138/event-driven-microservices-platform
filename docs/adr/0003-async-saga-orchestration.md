# ADR-0003: Async saga orchestration for distributed transactions

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Placing an order spans three services that each own their data: **order**,
**payment**, and **inventory**. There is no distributed ACID transaction across
them (and we would not want one). We need a way to keep them eventually consistent,
and to *undo* completed steps when a later step fails (e.g. payment captured but
inventory unavailable).

Two axes of choice:

1. **Choreography vs orchestration** — is the workflow implicit in services
   reacting to each other's events, or explicit in a coordinator?
2. **Sync vs async transport** — does the coordinator make blocking RPC calls, or
   exchange asynchronous messages?

## Decision

Use an **orchestration** saga, driven by **asynchronous messaging**.

- The **order-service hosts the saga orchestrator** as a distinct module.
- Saga state is **persisted** (a `saga_instance` row: current step, status, attempt
  count, deadline). Never held only in memory — a crash must not lose an in-flight saga.
- The orchestrator coordinates participants via **Kafka command messages and reply
  events**, not synchronous gRPC.
- Timeouts are **timer-driven** against the persisted deadline; a step with no reply
  is retried or compensated.
- The saga has a named **pivot step**: before the pivot, failure triggers compensation
  (undo); after the pivot, the saga must roll *forward* to completion. Non-compensatable
  side effects (e.g. sending a notification) happen only after the pivot.
- An order may transition to **CONFIRMED only after payment capture is durably
  established as succeeded**. A capture **timeout or unknown provider response is NOT
  success** — it moves the order to **`REQUIRES_RECONCILIATION`** (a non-terminal
  holding state) until the true outcome is resolved (ADR-0016), never a forced confirm
  or cancel.

### Why not choreography

Choreography gives compensation no natural home, smears the sequence across every
participant, creates implicit cyclic knowledge between services, and leaves no single
place to query "what state is this order's workflow in?". Orchestration keeps the
workflow explicit and observable.

### Why not synchronous gRPC

Orchestration is a *logical* pattern; it does not require synchronous calls. If the
orchestrator blocked on gRPC to each participant, order-service availability would
become the product of all downstream availabilities — a **distributed monolith**.
Asynchronous commands/replies decouple availability and absorb transient outages.

gRPC is retained only for **1–2 explicitly justified synchronous read paths** (see
ADR-0010), each with a timeout, circuit breaker, and bulkhead.

## Consequences

- **Positive:** Resilient to participant downtime; workflow state is queryable;
  compensation logic lives in one place; the saga is independently testable.
- **Negative:** More moving parts than a synchronous call chain; requires handling of
  **late replies** (a reply arriving after a timeout+compensation) via idempotent
  terminal states and reconciliation.
- **Follow-ups:** ADR-0004 (outbox for reliable publishing), ADR-0005 (idempotency),
  ADR-0006 (DLQ/retry). Saga state machine and failure matrix live in
  `docs/diagrams/`.
