# ADR-0006: Dead-letter queue and retry topology

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Consumers will encounter two very different failures: **transient** (a DB blip, a
downstream timeout) that will likely succeed on retry, and **permanent** (a
deserialization failure, a business-rule violation, a poison message) that will
never succeed no matter how often we retry. Treating them the same either drops
recoverable work or spins forever on poison messages. An undocumented DLQ is a black
hole where messages go to die unseen.

## Decision

Adopt a **non-blocking retry topology** with explicit exception classification:

- **Exception classification** at the consumer:
  - *Transient* (network/DB/provider timeout) → retry with backoff.
  - *Permanent technical* (deserialization, schema, poison, unexpected non-retryable) →
    route straight to the DLQ; do not waste retries.
  - **Expected business rejections are NOT DLQ material.** Insufficient stock, payment
    declined, etc. are *normal saga outcomes* carried as reply events
    (`StockReservationFailed`, `PaymentDeclined`) that drive compensation — they are
    successfully-processed messages, never poison. Only *technical* failures reach the DLQ.
- **Non-blocking retries via retry topics.** A failed transient message is forwarded
  to a delay/retry topic (e.g. `-retry-5s`, `-retry-1m`) rather than blocking the main
  partition. This preserves throughput on the main topic. We accept that retry topics
  break strict per-key ordering, and document which flows tolerate this (the saga is
  driven by explicit state, not message order, so it does).
- **DLQ per consumer group** (`<topic>.<group>.DLT`). A message exhausting retries, or
  classified permanent, lands here with failure metadata (exception, stack, attempt
  count, original headers).
- **Operational contract:** DLQ depth is **monitored and alerted**; a documented
  **replay runbook** (planned deliverable, see ROADMAP) describes triage and re-injection
  after a fix.

## Consequences

- **Positive:** Transient errors self-heal; poison messages are quarantined fast; the
  main topic keeps flowing; failures are visible and actionable.
- **Negative:** More topics to manage; retry topics relax ordering. Accepted, and
  scoped to flows that tolerate it.
- **See also:** [ADR-0014](0014-message-delivery-semantics.md) specifies the concrete
  backoff policy (exponential + jitter, max attempts), consumer offset-commit timing,
  and producer delivery settings that this topology relies on.
