# ADR-0010: Event envelope and command/event topic taxonomy

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

In an event-driven system the message contract *is* the integration contract. Two
mistakes are common and expensive: (1) conflating **commands** ("do this", directed at
one handler, may be rejected) with **events** ("this happened", a fact, fan-out to any
interested consumer); and (2) shipping bare payloads with no metadata for
correlation, tracing, ordering, or schema evolution.

## Decision

### Standard envelope

Every message (command or event) carries a versioned envelope:

| Field | Purpose |
|---|---|
| `messageId` (UUID) | Unique id; the key for consumer idempotency (ADR-0005). Used for commands and events |
| `messageKind` (enum) | `COMMAND` vs `EVENT` — makes the command/event distinction explicit on the wire, so dedup and routing logic is never ambiguous |
| `correlationId` | Ties all messages of one business flow together (e.g. one order) |
| `causationId` | The id of the message that directly caused this one |
| `occurredAt` (UTC) | When the fact happened / command was issued |
| `type` + `schemaVersion` | Message type and its schema version |
| `tenantId` | Tenant scope; `"default"` for now (ADR-0011) |
| `aggregateId` | The aggregate this concerns; used as the **Kafka partition key** |

`aggregateId` as the partition key guarantees per-aggregate ordering (all messages for
one order land on one partition, in order). Retry topics (ADR-0006) relax this, by design.

### Topic taxonomy

Commands and events are named and separated so their intent is unmistakable:

```
commands.<target-service>.<action>.v<major>     e.g. commands.payment.authorize.v1
events.<owning-service>.<fact>.v<major>          e.g. events.order.created.v1
<topic>.<consumer-group>.DLT                      dead-letter (ADR-0006)
```

### Schema management

- Schemas live in `shared/avro-schemas` and are registered in a **schema registry**.
- Compatibility mode is **BACKWARD** (new consumers read old data), enforced in CI so
  an incompatible change fails the build, not production.

### Caveat: the envelope `payload` is `bytes`, not an Avro union

The envelope carries the concrete message as a serialized `payload` (`bytes`), not as an
Avro union of every payload type. This keeps the envelope stable and lets new payload
types be added without touching the envelope schema — but it has a real consequence: the
**schema registry validates the envelope, not the payload inside it**. BACKWARD
compatibility on the envelope alone does not guarantee payload compatibility.

Therefore each **payload** schema is registered and compatibility-checked **separately**
in CI (its own subject), so payload evolution is still guarded. The alternative — a single
Avro union payload — would give one-step registry validation but couples every producer to
one ever-growing union and re-registers the envelope on every new message type. We chose
the `bytes` payload + per-payload subject checks deliberately; this note records that
trade-off so it is not mistaken for an oversight.

## Consequences

- **Positive:** Correlation and distributed tracing are possible end to end; ordering
  is well-defined; schema evolution is safe and CI-guarded; commands vs events are
  never confused.
- **Negative:** Envelope discipline and a registry to operate. Centralised in
  `common-lib` and infra so services do not reinvent it.
