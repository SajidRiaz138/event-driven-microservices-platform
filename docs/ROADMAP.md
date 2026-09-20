# Roadmap

This roadmap makes the platform's *deliberate deferrals* explicit. Everything here is
understood and intentionally postponed — not forgotten. Knowing when **not** to build
something is as much an architectural signal as building it.

## Delivery phases

| Phase | Scope | Status |
|---|---|---|
| **Phase 1** | Core saga slice: `api-gateway`, `auth-service`, `order-service`, `payment-service`, `inventory-service` — async saga, outbox everywhere, idempotency, DLQ, tracing, tests, one-command demo | In progress |
| **Phase 2** | `notification-service` (MongoDB) | Planned |
| **Phase 3** | `audit-service` (Cassandra), `ai-service` (Spring AI + pgvector) | Planned |

See [ADR-0012](adr/0012-phased-delivery.md) for the rationale.

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
