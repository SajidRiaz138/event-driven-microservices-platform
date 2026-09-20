# Event-Driven Microservices Platform

> A production-grade **order platform** demonstrating event-driven microservices with an
> **asynchronous saga**, **transactional outbox**, **idempotent** consumers, **DLQ/retry**,
> and **end-to-end observability** — built on **Spring Boot 4** and **Java 21**.

This repository is a portfolio flagship: it favours **depth and correctness** over breadth.
Every significant decision is captured as an [ADR](docs/adr/README.md), every requirement
is traceable to a scenario and a test, and the failure paths (compensation, redelivery,
lost payment responses) are first-class — not afterthoughts.

## The domain

A customer places an order; the platform reserves stock, takes payment, and confirms the
order, coordinating three independently-owned services via an async saga and staying
correct under failure.

```
Customer ──REST──▶ API Gateway ──▶ order-service (saga orchestrator)
                                        │  commands / reply events (Kafka)
                          ┌─────────────┼─────────────┐
                          ▼             ▼             ▼
                    inventory-svc   payment-svc   (audit/notify — later)
                    (reserve/TTL)   (auth→capture)
```

See the [C4 diagrams](docs/diagrams/c4-context-and-containers.md), the
[saga state machine](docs/diagrams/saga-state-machine.md), and the
[sequence diagrams](docs/diagrams/sequence-order-saga.md).

## Highlights (why this is senior-level)

- **Async saga orchestration** — persisted state machine, Kafka commands/replies, named
  pivot, timeouts, late-reply handling ([ADR-0003](docs/adr/0003-async-saga-orchestration.md)).
- **Transactional outbox in every producer** — no lost/phantom events ([ADR-0004](docs/adr/0004-transactional-outbox-everywhere.md)).
- **Three-layer idempotency** — edge key + consumer dedup + producer idempotence ([ADR-0005](docs/adr/0005-idempotency-strategy.md)).
- **Payment reconciliation** — unknown provider outcomes never double-charge ([ADR-0016](docs/adr/0016-payment-operations-and-reconciliation.md)).
- **No oversell** — atomic conditional reservation + optimistic locking ([ADR-0015](docs/adr/0015-concurrency-and-resource-management.md)).
- **Observability** — RED/USE metrics, structured logs, W3C tracing across REST→Kafka→DB ([ADR-0013](docs/adr/0013-observability-strategy.md)).

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 21 (virtual threads, records, sealed types) |
| Framework | Spring Boot 4.1.x |
| Build | Maven multi-module ([ADR-0002](docs/adr/0002-maven-multi-module-monorepo.md)) |
| Messaging | Apache Kafka; Avro messages ([ADR-0017](docs/adr/0017-messaging-technology-kafka.md)) |
| Persistence | PostgreSQL + Flyway; Redis (cache) ([ADR-0007](docs/adr/0007-polyglot-persistence-and-dev-simplification.md)) |
| APIs | REST (public) + Kafka (async); gRPC where justified |
| Deploy | Docker, Helm ([ADR-0008](docs/adr/0008-helm-primary-kustomize-deferred.md)), Kubernetes |
| Test | JUnit + Testcontainers (real Postgres/Kafka) |

## Run it

> Prerequisites: Java 21, Docker, Make.

```bash
cp .env.example .env      # dev defaults; no real secrets
make up                   # build + start the full stack (8 containers) and wait until healthy
make demo                 # scripted end-to-end: a confirmed order + a payment-decline compensation
make down                 # tear down
```

`make up` builds the four services and starts them alongside **Keycloak, PostgreSQL,
Kafka and Redis** (8 containers, with readiness health checks). `make demo` then, through
the API gateway with a real Keycloak-issued JWT:

1. places an order → polls until it reaches **`CONFIRMED`** (stock reserved, payment
   authorized then captured), and
2. places an order whose payment is declined → polls until the saga **compensates**
   (reservation released) and the order reaches **`CANCELLED` / `PAYMENT_DECLINED`**.

Each scenario prints its `correlationId` so you can follow the saga across every service
in the logs. `make smoke` is a fast liveness check; `make token` fetches a demo JWT; see
`make help` for all targets. Build-only, no Docker: `./mvnw verify` (runs unit +
Testcontainers integration tests).

### Running on Kubernetes

The same stack deploys to a local **minikube** cluster with one `helm install`, realising
[ADR-0008](docs/adr/0008-helm-primary-kustomize-deferred.md): a `platform-lib` library chart
holds the Deployment/Service/probe templates, four service charts are little more than their
`values.yaml`, and `platform-umbrella` adds the in-cluster infrastructure (PostgreSQL with
schema-per-service, single-broker Kafka in KRaft mode, Redis, Keycloak importing the same realm
export). `make demo` then runs unchanged against the cluster through a port-forwarded gateway.

```bash
minikube start -p edmp --memory=8192 --cpus=4
docker compose -f deploy/local/compose.yaml build && make k8s-images
make k8s-install
```

The command-by-command walkthrough — including how to follow one saga across pods by its
`correlationId`, and the failures worth knowing about — is
[docs/deployment/kubernetes-minikube.md](docs/deployment/kubernetes-minikube.md).

### Developing in IntelliJ IDEA

This is a standard **Maven multi-module** project — IntelliJ imports it natively:

1. **File → Open** and select the top-level `pom.xml` (the platform root). Choose
   *Open as Project*. IntelliJ detects the reactor and imports all modules.
2. Set the Project SDK to **Java 21** (Temurin/any JDK 21). The parent POM already pins
   `java.version=21`.
3. Run **Build → Build Project** (or `./mvnw install` once). The Avro plugin generates
   message classes into `shared/avro-schemas/target/generated-sources/avro`, which the
   build registers as a **generated source root** — IntelliJ picks this up automatically,
   so references to `Envelope`, `OrderCreated`, etc. resolve with no red code. If you open
   the project *before* the first build, run *Maven → Reload All Maven Projects* (or the
   build) once to trigger code generation.

IDE files (`.idea/`, `*.iml`) are git-ignored, so the project stays clean regardless of
editor.

## Documentation

| Topic | Link |
|---|---|
| Architecture decisions | [docs/adr/](docs/adr/README.md) |
| Requirements + scenarios | [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) |
| Non-functional targets | [docs/NFR-and-SLO.md](docs/NFR-and-SLO.md) |
| Event & command catalog | [docs/EVENT-CATALOG.md](docs/EVENT-CATALOG.md) |
| REST API guide | [docs/api/REST-API-GUIDE.md](docs/api/REST-API-GUIDE.md) |
| OpenAPI 3.1 spec | [shared/openapi/order-service.yaml](shared/openapi/order-service.yaml) |
| Diagrams | [docs/diagrams/](docs/diagrams/) |
| Kubernetes runbook (Helm, minikube) | [docs/deployment/kubernetes-minikube.md](docs/deployment/kubernetes-minikube.md) |
| Roadmap | [docs/ROADMAP.md](docs/ROADMAP.md) |
| Build log (order & status) | [docs/BUILD-LOG.md](docs/BUILD-LOG.md) |

## Status

**Phase 1 is complete and runs end-to-end** — a full vertical slice of five services
(api-gateway, auth via Keycloak, order, payment, inventory) that builds green, passes
its unit + Testcontainers integration suite, and demonstrates the order saga (confirmation
*and* compensation) live via `make demo`. Phase 2 (notifications) and Phase 3 (audit, AI)
are documented in the [roadmap](docs/ROADMAP.md) as deliberate, deferred work. See
[ADR-0012](docs/adr/0012-phased-delivery.md) for the phasing rationale and
[BUILD-LOG](docs/BUILD-LOG.md) for the detailed status.

## License

[MIT](LICENSE).
