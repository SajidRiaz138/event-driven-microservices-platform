# Architecture Decision Records (ADRs)

This log captures the significant architectural decisions for the Event-Driven
Microservices Platform, including the context, the options considered, the decision,
and its consequences. Each record is immutable once accepted; when a decision changes,
a new ADR supersedes the old one rather than editing history.

We use the [MADR](https://adr.github.io/madr/) format (lightweight).

## Status legend

| Status | Meaning |
|---|---|
| `Proposed` | Under discussion |
| `Accepted` | Decided and in effect |
| `Superseded` | Replaced by a later ADR (linked) |
| `Deprecated` | No longer relevant |

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-maven-multi-module-monorepo.md) | Maven multi-module monorepo | Accepted |
| [0003](0003-async-saga-orchestration.md) | Async saga orchestration for distributed transactions | Accepted |
| [0004](0004-transactional-outbox-everywhere.md) | Transactional outbox in every event-producing service | Accepted |
| [0005](0005-idempotency-strategy.md) | Three-layer idempotency strategy | Accepted |
| [0006](0006-dlq-and-retry-topology.md) | Dead-letter queue and retry topology | Accepted |
| [0007](0007-polyglot-persistence-and-dev-simplification.md) | Polyglot persistence + one-Postgres-schema-per-service in dev | Accepted |
| [0008](0008-helm-primary-kustomize-deferred.md) | Helm as primary packaging, Kustomize deferred | Accepted |
| [0009](0009-per-service-jwt-resource-server.md) | Per-service JWT validation (no confused deputy) | Accepted |
| [0010](0010-event-envelope-and-topic-taxonomy.md) | Event envelope and command/event topic taxonomy | Accepted |
| [0011](0011-multitenancy-tenant-id-from-day-one.md) | Carry tenantId from day one (default tenant) | Accepted |
| [0012](0012-phased-delivery.md) | Phased delivery — runnable vertical slice first | Accepted |
| [0013](0013-observability-strategy.md) | Observability strategy — metrics, logs, traces, alerting | Accepted |
| [0014](0014-message-delivery-semantics.md) | Message delivery semantics — retry, backoff & offset management | Accepted |
| [0015](0015-concurrency-and-resource-management.md) | Concurrency & resource management (virtual threads, HikariCP, admission control) | Accepted |
| [0016](0016-payment-operations-and-reconciliation.md) | Payment operations model & unknown-state reconciliation | Accepted |
| [0017](0017-messaging-technology-kafka.md) | Messaging technology — Apache Kafka | Accepted |
| [0018](0018-auth-service-keycloak.md) | Auth-service engine — Keycloak | Accepted |

## Roadmapped decisions (not yet made)

These are deliberately deferred and tracked in [`../ROADMAP.md`](../ROADMAP.md):
CQRS read models, event sourcing, GitOps (ArgoCD), service mesh (mTLS), chaos engineering,
Backstage catalog, and the auth-service engine choice (Keycloak vs Spring Authorization Server).
