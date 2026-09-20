# ADR-0012: Phased delivery — runnable vertical slice first

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The full vision is eight services across several datastores. Attempting to build all
of it at once risks the worst outcome for a portfolio: a broad but unfinished system
that nobody can run. An unfinished system signals *poor scoping* — the very skill the
work is meant to demonstrate. A complete, running, self-explaining slice is worth more
than three services a reviewer never starts.

## Decision

Deliver in **phases**, each independently complete and runnable. The full eight-service
vision remains the documented target; it is built in order of value.

- **Phase 1 — the core saga slice (must fully work and self-demo):**
  `api-gateway`, `auth-service`, `order-service`, `payment-service`, `inventory-service`.
  Includes: async saga orchestration (ADR-0003), transactional outbox in every producer
  (ADR-0004), three-layer idempotency (ADR-0005), DLQ + retry (ADR-0006), end-to-end
  W3C trace context, tests (unit + Testcontainers), and a **one-command demo** with
  seeded data and a scripted payment-failure scenario that visibly triggers saga
  compensation.
- **Phase 2 — `notification-service`** (MongoDB, if its document argument holds).
- **Phase 3 — `audit-service` (Cassandra) and `ai-service` (Spring AI + pgvector)**,
  built only once Phase 1 is polished, and only if each earns its place.

Advanced patterns (CQRS, event sourcing, GitOps, service mesh, chaos) stay in
[`../ROADMAP.md`](../ROADMAP.md) as documented, deliberate deferrals.

## Consequences

- **Positive:** There is always a working, demonstrable system; scope is controlled;
  quality is visible over breadth.
- **Negative:** The most advanced services appear later. Accepted — depth first.
