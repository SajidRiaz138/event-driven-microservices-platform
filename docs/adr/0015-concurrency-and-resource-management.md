# ADR-0015: Concurrency & resource management model

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Enabling Java 21 virtual threads is often treated as a free throughput win. It is not:
virtual threads remove the *thread* as the scarce resource, which simply **moves the
bottleneck downstream** to the next bounded resource — almost always the **database
connection pool**. Without a deliberate model tying the web connector, virtual threads,
an admission limit, and the pool together, a service under load will either exhaust its
DB pool and pile up, or accept unbounded work and fall over. This ADR states the
end-to-end concurrency model so behaviour under load is intentional.

## Decision

### 1. Web tier — Tomcat with virtual-thread executor

- Spring Boot 4 on **Tomcat** with `spring.threads.virtual.enabled=true`: each request
  runs on a **virtual thread**, so there is no small bounded worker pool acting as the
  limiter.
- The connector still bounds intake: `server.tomcat.max-connections` (concurrent
  connections) and `server.tomcat.accept-count` (the OS accept queue once
  max-connections is reached). Beyond these, new connections are refused fast rather
  than queued forever — fail fast, not fail slow.
- Because virtual threads are cheap, the *thread count* is no longer the throughput
  ceiling; the ceiling is the slowest bounded downstream resource (below).

### 2. Data tier — HikariCP as the real throughput ceiling

- **HikariCP** is the connection pool. Sizing follows the "connections are precious"
  principle: a **small** pool (`maximumPoolSize` ≈ (core_count × 2) + effective_spindle,
  tuned per service; Phase-1 default **10**), **not** hundreds.
- `connectionTimeout` (fail fast when the pool is exhausted, e.g. 2–5s),
  `maxLifetime` < DB/infra idle timeout, `leakDetectionThreshold` on in dev to catch
  unreturned connections.
- **This pool is the deliberate throughput limiter.** A service can admit thousands of
  virtual threads, but only `maximumPoolSize` can touch the DB at once; the rest wait
  for a connection (bounded by `connectionTimeout`).

### 3. Admission control — bound work to the bottleneck

- A **semaphore admission gate** (in `common-lib`) caps concurrent in-flight
  DB-bound work per service to **≈ the pool size**, so virtual threads cannot admit far
  more concurrency than the pool can serve. Excess requests are rejected quickly with
  `429 Too Many Requests` (+ `Retry-After`) rather than blocking on an exhausted pool
  and inflating latency for everyone.
- Net request-admission chain, end to end:

  ```
  client → Tomcat connector (max-connections / accept-count)
         → virtual thread per request (cheap, not the limiter)
         → admission semaphore (≈ pool size)  ← sheds load early (429)
         → HikariCP (maximumPoolSize)          ← the true ceiling
         → PostgreSQL
  ```

### 4. Transactions, isolation & locking (ACID)

- Each service's writes are **ACID within its own PostgreSQL transaction**
  (cross-service is eventual, ADR-0003).
- **READ COMMITTED** (Postgres default) for most paths. Where a read-modify-write must
  not race — notably the **inventory reservation** — we use **optimistic locking**
  (`@Version`) plus an **atomic conditional UPDATE** (`... WHERE available >= :qty`), so
  oversell is impossible without escalating to SERIALIZABLE. SERIALIZABLE is reserved
  for the rare invariant that genuinely needs it, with documented retry-on-conflict.
- Transactions are kept **short** and never span a network/RPC call (a DB connection is
  never held while waiting on Kafka or another service) — long transactions are the
  fastest way to exhaust a small pool.

### 5. Virtual-thread pitfalls explicitly managed

- **Pinning:** avoid `synchronized` around blocking I/O (pins the carrier thread); use
  `ReentrantLock`. JDBC drivers that pin are noted and monitored.
- **ThreadLocal:** correlation/tenant context uses scoped values / explicit propagation,
  not unbounded ThreadLocal accumulation across millions of virtual threads.
- **No thread pooling of virtual threads** — they are created per task, never pooled.

## Consequences

- **Positive:** Behaviour under overload is intentional — load is shed early at the
  admission gate (429) instead of degrading everyone; the DB pool is protected; the
  throughput ceiling is explicit and tunable; oversell is structurally impossible.
- **Negative:** More configuration and an admission gate to maintain; requires **planned
  load testing (Phase 2, see ROADMAP)** to tune pool/semaphore sizes against the 500 orders/sec
  target. Accepted — this *is* the scalability story.
- **Related:** NFR §3, §4, §8; ADR-0007 (persistence), ADR-0013 (pool/saturation
  metrics on dashboards).
