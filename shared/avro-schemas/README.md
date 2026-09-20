# Avro Schemas — canonical message contracts

These Avro schemas are the **single source of truth** for every asynchronous message
on the platform. They are used for:

1. **Kafka serialization** — registered in the schema registry; **BACKWARD**
   compatibility is enforced in CI so an incompatible change fails the build, not
   production.
2. **Cassandra audit records** (Phase 3) — the audit-service persists these same event
   payloads, so the stored audit record and the Kafka message share one definition and
   cannot drift.

See [`../../docs/EVENT-CATALOG.md`](../../docs/EVENT-CATALOG.md) for the full inventory
and [ADR-0010](../../docs/adr/0010-event-envelope-and-topic-taxonomy.md) for the rules.

## Layout

```
avro-schemas/
├── pom.xml                       # avro-maven-plugin codegen (generate-sources)
└── src/main/avro/
    ├── Envelope.avsc             # the wrapper for every message (messageId, messageKind, ...)
    ├── common/Money.avsc         # shared type: minor-units + currency, never a float
    ├── events/                   # facts (past tense) — events.<owner>.<fact>.vN
    │   ├── OrderCreated.avsc  OrderConfirmed.avsc  OrderCancelled.avsc
    │   ├── PaymentAuthorized.avsc  PaymentCaptured.avsc (pivot)  PaymentDeclined.avsc
    │   ├── PaymentCaptureFailed.avsc  PaymentRefunded.avsc
    │   └── StockReserved.avsc  StockReservationFailed.avsc  StockReleased.avsc
    └── commands/                 # imperatives — commands.<target>.<action>.vN
        ├── AuthorizePayment.avsc  CapturePayment.avsc  RefundPayment.avsc
        └── ReserveStock.avsc  ReleaseStock.avsc
```

The full Phase-1 message set is materialised and generates Java at build time
(`./mvnw -pl shared/avro-schemas install`).

## Conventions

- **Namespaces** mirror the topic taxonomy: `com.sajidriaz.orderplatform.events.<owner>` /
  `...commands.<target>`.
- **Money** is always the shared `Money` type — never a `float`/`double`.
- **Timestamps** use Avro logical type `timestamp-millis` (UTC).
- **UUIDs** use logical type `uuid`.
- **Evolution:** add fields with defaults (BACKWARD compatible); never remove or
  repurpose a field within a major version — bump the topic version (`.v2`) instead.

## Codegen

The `shared/avro-schemas` Maven module runs the Avro Maven plugin to generate Java
classes from these `.avsc` files at build time; services depend on the module rather
than hand-writing DTOs. (Wired up in the build phase.)

> Only a representative subset of payload schemas is materialised here to establish the
> pattern and conventions; the remaining catalog entries are added alongside the
> services that own them, during the build phase.
