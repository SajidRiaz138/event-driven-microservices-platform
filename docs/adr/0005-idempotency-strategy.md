# ADR-0005: Three-layer idempotency strategy

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The platform is at-least-once end to end: clients retry HTTP calls, the outbox
re-publishes on crash (ADR-0004), and Kafka redelivers on consumer restart. Without
deliberate idempotency, retries create duplicate orders, double charges, and
double stock decrements. "Idempotency" is commonly treated as one problem; it is
actually three, at three boundaries.

## Decision

Handle idempotency explicitly at each boundary:

1. **Edge (HTTP) — client-supplied `Idempotency-Key`.**
   Mutating endpoints (e.g. create order) require an `Idempotency-Key` header. The
   first request is processed and its response stored keyed by that value; retries
   return the stored response instead of acting again.

2. **Consumer (Kafka) — dedup table written in the business transaction.**
   Each consumer records `(event_id, consumer_group)` in a `processed_message` table
   **in the same DB transaction** as the business change. A redelivered event whose
   id is already present is acknowledged and skipped. Because the dedup insert and the
   business write share one transaction, they cannot diverge.

3. **Publisher — accept at-least-once, do not chase exactly-once.**
   We explicitly **do not** enable Kafka exactly-once (EOS). Its coordination and
   throughput cost buys nothing once consumers deduplicate. Publishing stays simple
   and at-least-once.

## Consequences

- **Positive:** Safe retries everywhere; no double charge or double decrement;
  simple, cheap mechanisms with clear ownership.
- **Negative:** A dedup table per consuming service and an idempotency store at the
  edge. Accepted as the cost of correctness under at-least-once delivery.
