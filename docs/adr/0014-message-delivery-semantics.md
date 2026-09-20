# ADR-0014: Message delivery semantics — retry, backoff & offset management

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The platform claims **at-least-once** delivery and "retry with backoff"
(ADR-0004, ADR-0005, ADR-0006), but the *mechanisms* that produce those guarantees
were not written down: when the consumer commits its offset, the producer's delivery
settings, the concrete backoff policy, and how a consumer's retry budget composes with
the saga's own deadlines. "We do retries" without these specifics is not a design. This
ADR pins them down so the behaviour is deterministic and reviewable.

## Decision

### 1. Consumer offset management → the source of at-least-once

- Consumers use **manual acknowledgement**, committing the offset **only after the
  message is successfully processed** (business change + `processed_message` dedup row
  committed in one DB transaction, ADR-0005).
- Consequence, by design: if the service crashes after processing but before the
  commit, Kafka **redelivers** on restart. That is *why* delivery is at-least-once and
  *why* consumer dedup is mandatory, not optional. The two decisions are inseparable.
- `enable.auto.commit=false`. `max.poll.records` and `max.poll.interval.ms` are tuned
  so a batch cannot exceed the poll interval (a slow batch must not trigger a rebalance
  storm).

### 2. Producer delivery guarantees

- `acks=all` — a write is acknowledged only after the in-sync replica set has it (no
  data loss on a broker failure).
- `enable.idempotence=true` — the **producer** de-duplicates its *own* retried sends
  (a network hiccup mid-publish will not create a duplicate on the partition). This is
  distinct from and complementary to consumer-side dedup: the producer prevents
  duplicate *records*, the consumer tolerates duplicate *deliveries*.
- `retries` high + `delivery.timeout.ms` bounds total time; `max.in.flight.requests=5`
  (safe with idempotence enabled and preserves per-partition ordering).
- The **outbox relay** (ADR-0004) treats a publish failure as "leave the row
  unpublished" — it simply retries on the next poll, so relay publishing inherits the
  same at-least-once property without extra machinery.

### 3. Backoff policy (retry topics, ADR-0006)

- **Exponential backoff with full jitter.** Fixed backoff synchronises retries across
  consumers and creates thundering-herd retry storms; jitter spreads them.
- Concrete Phase-1 policy:
  - base = 1s, multiplier = 2, max interval = 60s, **full jitter** applied per attempt.
  - **max 5 attempts**, then the message is routed to the DLQ.
  - implemented as **non-blocking retry topics** (`-retry-1s`, `-retry-10s`,
    `-retry-1m`) so a retrying message never blocks the main partition (ADR-0006).
- **Exception classification still wins:** a *permanent* error (deserialization,
  validation, business rule) skips retries and goes **straight to the DLQ** — backoff
  applies only to *transient* errors.

### 4. Retry budget vs saga deadline (composition)

- A consumer's retry budget is **bounded by the saga step deadline** (ADR-0003). A
  message must not still be retrying after the orchestrator has already timed the step
  out and compensated — that is the classic source of "late replies."
- Late replies that still arrive are handled by idempotent terminal states and
  reconciliation (ADR-0003 §consequences), not by state mutation.

### 5. What we explicitly do NOT do

- **No Kafka exactly-once (EOS)** across the app (ADR-0005). `acks=all` +
  `enable.idempotence=true` give safe *publishing*; consumer dedup gives safe
  *processing*. EOS transactions add coordination and throughput cost that these two
  cheaper mechanisms already make unnecessary.

## Consequences

- **Positive:** Delivery behaviour is deterministic and defensible; retry storms are
  avoided via jitter; poison messages are bounded (5 attempts → DLQ); no data loss on
  broker or consumer failure; the "at-least-once" claim now has an explicit mechanism.
- **Negative:** Manual offset handling and retry-topic tiers are more code than
  auto-commit; centralised in `common-lib` so services do not each reinvent it.
- **Related:** ADR-0004 (outbox), ADR-0005 (idempotency), ADR-0006 (DLQ/retry),
  ADR-0003 (saga deadlines). Backoff numbers are validated under load
  (`testing/load/`) and surfaced on the observability dashboards (ADR-0013).
