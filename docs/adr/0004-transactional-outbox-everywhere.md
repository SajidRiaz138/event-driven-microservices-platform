# ADR-0004: Transactional outbox in every event-producing service

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Every service that changes its database *and* publishes an event faces the
**dual-write problem**: the DB commit and the Kafka publish are two separate systems
and cannot share one transaction. If the process crashes between them, we either
lose an event (DB committed, publish never happened) or emit a phantom (publish
happened, DB rolled back). In this platform that failure lands exactly where money
(payment) and stock (inventory) live — a payment captured but never announced leaves
the saga hung forever.

## Decision

Use the **transactional outbox** pattern in **every** event-producing service —
order, payment, and inventory — not just the orchestrator.

- In the same DB transaction as the business change, insert a row into an `outbox`
  table (event id, aggregate id, type, payload, headers, `occurred_at`).
- A separate **relay** polls unpublished outbox rows and publishes them to Kafka,
  marking them sent. The poll uses `SELECT ... FOR UPDATE SKIP LOCKED` so multiple
  service replicas can drain the outbox concurrently without double-publishing.
- The business change and the intent to publish now commit atomically. Publishing is
  **at-least-once** (a crash after publish, before marking sent, re-publishes) —
  consumers deduplicate (ADR-0005).

### Why polling relay, not CDC (Debezium) here

A polling relay with `SKIP LOCKED` needs no extra infrastructure and is trivial to
run and reason about — the right choice for this platform. Log-based CDC
(Debezium/Kafka Connect) is the higher-throughput production alternative and is
documented as such, but is not required to demonstrate the pattern.

## Consequences

- **Positive:** No lost or phantom events anywhere in the system; the most common
  event-driven correctness bug is eliminated by construction.
- **Negative:** Slight publish latency (poll interval); an `outbox` table and relay in
  every producing service. Accepted — correctness outweighs the cost.
- **Note:** The earlier plan placed the outbox only in order-service; that was a bug,
  corrected here to *every* producer.
