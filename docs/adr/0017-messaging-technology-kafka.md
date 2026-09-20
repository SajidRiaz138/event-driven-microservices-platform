# ADR-0017: Messaging technology — Apache Kafka

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The platform is event-driven and refers throughout to topics, commands, events, DLTs,
partition keys and a schema registry, but never *names* the broker. The choice affects
ordering guarantees, retention (event replay), the retry/DLQ topology, and the schema
registry. It must be an explicit decision, not an implicit assumption.

## Decision

**Apache Kafka is the Phase 1 message broker.**

- **Why Kafka (not RabbitMQ/others here):** durable, replayable log (an event-sourced
  audit and outbox replay rely on retention); partitioned ordering; mature schema
  registry ecosystem; the de-facto standard for this class of system, so it reads
  immediately to reviewers. RabbitMQ suits routing-heavy/RPC workloads but does not give
  a retained, replayable log — which this platform's outbox, audit and reconciliation
  stories want.

- **Partitioning & ordering:** partition key = **`aggregateId`** (the `orderId` for the
  order flow). All messages for one order land on one partition, preserving per-order
  ordering while allowing parallelism across orders. `sagaId == orderId` for Phase 1, so
  saga messages share the order's partition.

- **Topic taxonomy:** as defined in ADR-0010 —
  `commands.<target>.<action>.v<major>`, `events.<owner>.<fact>.v<major>`,
  `<topic>.<consumer-group>.DLT`, plus retry topics per ADR-0006/0014.

- **Consumer groups:** one consumer group per (service, purpose). The saga orchestrator,
  each participant, and (later) audit/notification each consume with their own group, so
  the same event fans out independently with independent offsets and DLQs.

- **Schema:** Avro (ADR-0010) in a schema registry; **BACKWARD** compatibility enforced
  in CI.

- **Retention:** business-event topics retain long enough to support replay and audit
  ingestion (Phase 1 dev: days; production: sized per compliance). DLTs retained until
  triaged.

- **Delivery semantics:** `acks=all` + producer idempotence; consumer offset-after-
  processing; at-least-once + consumer dedup (ADR-0014). No EOS.

## Consequences

- **Positive:** Ordering, replay, retry/DLQ and schema evolution all have a concrete,
  standard home; the earlier implicit assumption is now a decision reviewers can check.
- **Negative:** Kafka + schema registry are heavier to run locally than a simple queue;
  mitigated by the one-command local stack (ADR to follow for the demo) and by keeping
  Phase 1 to a single-broker dev setup.
- **Related:** ADR-0006, ADR-0010, ADR-0014.
