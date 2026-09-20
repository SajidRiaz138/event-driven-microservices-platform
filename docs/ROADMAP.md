# Roadmap

This roadmap makes the platform's *deliberate deferrals* explicit. Everything here is
understood and intentionally postponed — not forgotten. Knowing when **not** to build
something is as much an architectural signal as building it.

## Delivery phases

| Phase | Scope | Status |
|---|---|---|
| **Phase 1** | Core saga slice: `api-gateway`, `auth-service` (Keycloak), `order-service`, `payment-service`, `inventory-service` — async saga, outbox everywhere, idempotency, DLQ, tracing, tests, one-command demo, Helm/minikube deploy | ✅ Complete |
| **Phase 2** | `notification-service` (MongoDB) | Planned |
| **Phase 3** | `audit-service` (Cassandra), `ai-service` (Spring AI + pgvector) | Planned |

See [ADR-0012](adr/0012-phased-delivery.md) for the rationale.

## Planned services (Phase 2/3) — requirements

These services are **not yet built**; their scaffolding was removed so the repository
contains only what actually runs. Each has a clear, deferred requirement set here (and the
supporting ADRs already exist), so building them later is additive, not exploratory. They
will consume the existing Avro events — no new producer work in Phase 1 is needed.

### notification-service (Phase 2) — MongoDB
- **Purpose:** deliver customer notifications (email/SMS/push) reacting to order lifecycle events.
- **Consumes:** `events.order.confirmed.v1`, `events.order.cancelled.v1` (fan-out; own consumer group).
- **Persistence:** **MongoDB** — flexible per-channel message documents (each channel has a
  different payload shape), justifying a document store over relational ([ADR-0007](adr/0007-polyglot-persistence-and-dev-simplification.md)).
- **Key requirements:** idempotent consumption (`processed_message` equivalent — dedup on
  `messageId`); notifications only fire on **terminal, post-pivot** states (never mid-saga);
  template + channel abstraction; delivery-state tracking; transactional-outbox pattern if it
  emits its own events.

### audit-service (Phase 3) — Cassandra
- **Purpose:** an immutable, high-volume audit log of every platform event.
- **Consumes:** **all** `events.*` topics (and optionally commands) — append-only sink.
- **Persistence:** **Cassandra** — masterless, write-optimised, linear-scale for append-heavy
  audit data ([ADR-0007](adr/0007-polyglot-persistence-and-dev-simplification.md)). Tables
  designed **query-first**; partition keys **time-bucketed** to avoid unbounded partitions.
- **Key requirements:** the audited record reuses the **same Avro event schemas** as Kafka
  (single source of truth, no drift); write idempotency on `messageId`; retention/compliance
  policy; no PII beyond what the events carry (crypto-shredding story documented).

### ai-service (Phase 3) — Spring AI + pgvector
- **Purpose:** a concrete, bounded AI feature — e.g. an order-support RAG assistant answering
  "where is my order / why was it cancelled" from order history, or anomaly flagging on the
  audit stream. Named use case required before building (avoid resume-driven scope).
- **Persistence:** **pgvector** (embeddings in PostgreSQL — no new engine) + **Redis** for
  response/embedding cache.
- **Key requirements:** **Spring AI** integration (requires Spring Boot 4, already the
  platform baseline); RAG over order/audit data; per-service JWT auth like every other service
  ([ADR-0009](adr/0009-per-service-jwt-resource-server.md)); cost/latency guardrails; no secret
  or PII leakage into prompts/logs.

See [ADR-0012](adr/0012-phased-delivery.md) (phasing) and
[ADR-0007](adr/0007-polyglot-persistence-and-dev-simplification.md) (why each datastore).

## Deferred architectural capabilities

Each item lists *why it is deferred* and *what would trigger building it*.

| Capability | Why deferred | Trigger to build |
|---|---|---|
| **CQRS read models** | Phase 1 reads are simple; premature split adds cost | A read model whose shape diverges sharply from the write model |
| **Event sourcing** | High complexity; must be done well or it harms the design | A domain (e.g. order) that genuinely needs full audit/replay of state transitions |
| **GitOps (ArgoCD/Flux)** | Helm + CI is enough to demonstrate deployment (ADR-0008) | A real cluster and a promotion workflow across environments |
| **Service mesh (mTLS, Istio/Linkerd)** | A half-installed mesh is worse than none; per-hop JWT + NetworkPolicy is honest for now (ADR-0009) | Need for transparent mTLS and traffic policy at scale |
| **Chaos engineering** | Requires a stable running platform to be meaningful | Phase 1 stable and observable; then inject failures to validate resilience |
| **Multi-tenancy (full)** | Out of scope; but `tenantId` is already threaded through (ADR-0011) | A real second tenant / isolation requirement |
| **Backstage / TechDocs catalog** | Organisational maturity concern, not core architecture | Multiple teams/services needing a catalog |

## Decided

- **auth-service engine: Keycloak** — see [ADR-0018](adr/0018-auth-service-keycloak.md).
