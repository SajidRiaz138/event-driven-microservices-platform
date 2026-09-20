# Non-Functional Requirements (NFRs) & Service-Level Objectives (SLOs)

This page states the platform's quality targets in concrete, measurable terms.
Numbers are the *design targets* the architecture is built to meet. They are validated by
**planned load tests (Phase 2, see [ROADMAP](ROADMAP.md))** and observed on the
observability dashboards; the targets are stated now so the design is accountable to them.

## 1. Latency (SLO)

| Flow | Target | Notes |
|---|---|---|
| `POST /orders` (accept) | p99 < 150 ms | Returns **202 Accepted** + status URL; saga runs async |
| Order reaches terminal state (CONFIRMED/CANCELLED) | p95 < 3 s | End-to-end saga, happy path |
| Read endpoints (`GET /orders/{id}`) | p99 < 100 ms | Cache-assisted where applicable |
| Inventory availability read | p99 < 50 ms | Redis-fronted, display-only |

## 2. Availability (SLO)

| Component | Target | Error budget (30d) |
|---|---|---|
| Edge (api-gateway) | 99.9% | ~43 min |
| Core write path (order intake) | 99.9% | ~43 min |
| Saga completion (async) | 99.5% | ~3.6 h |

The async saga design (ADR-0003) means participant downtime degrades *latency*, not
*availability*, of order intake — orders are accepted and complete when downstreams recover.

## 3. Throughput & scalability

- Design point: **500 orders/sec** sustained at the edge, horizontally scalable by
  adding stateless service replicas.
- Kafka partitions sized so per-aggregate ordering holds while allowing consumer
  parallelism (partition key = `aggregateId`, ADR-0010).

## 4. Consistency & ACID

- **Eventual consistency** across services; **ACID within a service's own PostgreSQL
  transaction** (atomicity, consistency, isolation, durability).
- **Isolation level:** READ COMMITTED (Postgres default) for most paths.
  Read-modify-write races (notably the **inventory reservation**) are handled with
  **optimistic locking** (`@Version`) + an **atomic conditional UPDATE**
  (`... WHERE available >= :qty`), so oversell is impossible without escalating to
  SERIALIZABLE. SERIALIZABLE is reserved for invariants that truly need it, with
  retry-on-serialization-failure. Full model in ADR-0015.
- Transactions are **short** and never span an RPC/Kafka call (a DB connection is never
  held while awaiting another service) — protects the connection pool.
- Order intake is **read-your-writes safe** via the 202 + status-URL pattern (never a
  fake 201). Clients poll/subscribe for the terminal state.
- Inventory oversell is prevented by the atomic conditional decrement above; the Redis
  cache is **display-only and never backs a reservation decision** (ADR-0007).

## 5. Durability & reliability

- No lost/phantom events (transactional outbox, ADR-0004).
- At-least-once delivery + consumer dedup (ADR-0005) → effectively once-processed.
  Delivery mechanism (offset-after-processing, `acks=all` + producer idempotence,
  exponential backoff with jitter, max attempts → DLQ) is specified in ADR-0014.
- Poison messages quarantined to DLQ within the retry budget (ADR-0006).
- RPO for committed business data = 0 (DB is source of truth); events are replayable.

## 6. Observability

- **100% of requests carry a W3C `traceparent`**, propagated across REST → Kafka → gRPC.
- RED metrics (Rate, Errors, Duration) per service; USE metrics for infra.
- Saga dashboard: in-flight sagas, step durations, compensation rate, DLQ depth (alerted).
- Structured JSON logs including `correlationId` on every line.

## 7. Security

- All external and internal calls authenticated via JWT, validated per service (ADR-0009).
- Money represented as minor-unit `long` or `BigDecimal` + currency — **never** `double`.
- No PAN or raw card data stored (payment integrates a provider abstraction).
- Secrets never in images or Git; delivered via a secrets manager (roadmap: ESO/SOPS).

## 8. Resource & runtime (concurrency model)

Full rationale in ADR-0015. Summary:

- **Web tier:** Tomcat with **Java 21 virtual threads**
  (`spring.threads.virtual.enabled=true`) — a virtual thread per request, so the thread
  count is not the ceiling. Intake is bounded by `server.tomcat.max-connections` and
  `server.tomcat.accept-count` (accept queue); excess connections are refused fast.
- **Data tier:** **HikariCP** is the deliberate throughput ceiling — a **small** pool
  (`maximumPoolSize` ≈ 10 in Phase 1, not hundreds), short `connectionTimeout` (fail
  fast when exhausted), `maxLifetime` under infra idle timeout, `leakDetectionThreshold`
  in dev. Only `maximumPoolSize` requests touch the DB at once.
- **Admission control:** a **semaphore gate** (in `common-lib`) caps in-flight DB-bound
  work to ≈ the pool size, so virtual threads cannot admit more concurrency than the
  pool can serve. Overload sheds early with **429 + Retry-After**, not by degrading
  latency for everyone. End-to-end chain:
  `connector (max-connections/accept-count) → virtual thread → admission semaphore → HikariCP (maximumPoolSize) → PostgreSQL`.
- **Virtual-thread pitfalls managed:** no `synchronized` around blocking I/O (pinning) —
  use `ReentrantLock`; scoped values instead of unbounded ThreadLocals; virtual threads
  are never pooled.
- **Readiness** probes must **not** fail on a transient Kafka/broker blip, to avoid a
  broker hiccup cascading into a platform outage.
- Target container start-to-ready < 20 s; images distroless, non-root.

## 9. Testability

- Unit tests for domain logic; **Testcontainers** integration tests against real
  Postgres/Kafka/Redis; a documented approach for **testing the saga** (happy path,
  each failure branch, late-reply handling).

---

*These targets are deliberately modest and defensible for a demonstrator; the point is
that they are **stated, measurable, and traceable** to architectural choices.*
