# ADR-0013: Observability strategy — metrics, logs, traces, alerting

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

This is a highly distributed, event-driven system: a single business action (place an
order) fans out across the gateway, order-service, Kafka, payment-service and
inventory-service, crossing REST, asynchronous messaging and (occasionally) gRPC. When
something is slow or wrong, "which service, which hop, which message?" is unanswerable
without deliberate observability. In distributed systems, **observability is not
operational polish — it is a functional requirement**: the system is only debuggable
and operable to the degree it is observable.

Observability was previously referenced in several places (NFR page, DLQ alerting in
ADR-0006) but never decided as one coherent strategy. This ADR consolidates it.

## Decision

Adopt the **three pillars — metrics, logs, traces — as one correlated whole**,
standardised across every service via `common-lib` so no service reinvents it.

### 1. Instrumentation standard

- **Micrometer** is the metrics facade; **OpenTelemetry (OTel)** is the tracing (and,
  where practical, metrics) standard. Spring Boot Actuator exposes health, info and a
  Prometheus scrape endpoint in every service.
- Instrumentation lives in `common-lib` as an auto-configuration, so adding a service
  gets metrics, structured logging and trace propagation for free.

### 2. Metrics (Prometheus + Grafana)

- **RED** for every service: **R**ate, **E**rrors, **D**uration (per endpoint and per
  Kafka consumer).
- **USE** for infrastructure: **U**tilisation, **S**aturation, **E**rrors
  (CPU/mem/pool/queue depth).
- **Domain/SLI metrics** that map directly to the SLOs in `NFR-and-SLO.md`:
  order-accept latency, saga completion time, **in-flight sagas**, **compensation
  rate**, **DLQ depth**, outbox lag (unpublished rows / oldest unpublished age),
  cache hit ratio, DB pool saturation.
- Curated **Grafana dashboards** (delivered with the observability stack, see ROADMAP):
  a per-service RED board and a **saga board** (the platform's signature view).

### 3. Logging (structured, correlated)

- **Structured JSON logs** only (no free-form text) so they are queryable.
- Every log line carries `correlationId`, `traceId`, `spanId`, `service`, `tenantId`,
  and — where relevant — `orderId`/`sagaId`. Correlation is put into the logging MDC at
  the edge and propagated across REST headers and Kafka message headers, so one
  `correlationId` stitches together the logs of an entire business flow across services.
- **Log levels** are meaningful: ERROR = needs attention, WARN = degraded/anomalous,
  INFO = business milestones (order accepted, saga step completed), DEBUG = dev only.
- **No PII or secrets in logs.** Card data, tokens and personal data are never logged;
  fields are masked in `common-lib`'s serializer. (Ties to the security ADR-0009 and
  retention/PII notes.)
- Local/dev: logs to stdout (twelve-factor). A log aggregator (e.g. Loki/ELK) is the
  production target and is wired via the same JSON output — no code change.

### 4. Tracing (distributed, end-to-end)

- **W3C Trace Context** (`traceparent`) is generated at the edge and propagated across
  **REST → Kafka (message headers) → gRPC → DB spans**. This yields a single end-to-end
  trace for an order, *including the asynchronous saga hops* — the hardest and most
  valuable trace to get right in an event-driven system.
- OTel exports to a collector; the dev backend is Jaeger/Tempo. Sampling is
  head-based in dev (100%) and tail/prob-based in production (config only).

### 5. Alerting (SLO-driven, low-noise)

- Alerts are tied to **SLOs and error budgets** (`NFR-and-SLO.md`), not to raw
  resource thresholds — alert on symptoms users feel, not on every CPU spike.
- Phase 1 alert set: order-accept latency SLO burn, saga compensation-rate spike,
  **DLQ depth > 0 with rate**, outbox lag growing (relay stalled), edge 5xx rate,
  readiness flaps. Each alert links to a **runbook** (operational runbooks are a planned
  deliverable alongside the observability stack — see ROADMAP).

### 6. Correlation across the pillars (the point)

The design goal is one-click pivoting: a **metric** spike → the exemplar **trace**
behind it → the structured **logs** for that `traceId`. `traceId` and `correlationId`
are the shared join keys across all three pillars. Observability that cannot pivot
between pillars is three silos, not one system.

## Consequences

- **Positive:** The distributed system is debuggable; incidents are triaged from
  symptom to root cause quickly; SLOs are measurable and enforceable; the saga is
  observable end to end — a genuine differentiator.
- **Negative:** Instrumentation overhead and an observability stack to run
  (Prometheus, Grafana, OTel collector, a trace backend) in the local compose.
  Mitigated by centralising instrumentation in `common-lib` and treating the o11y
  stack as standard infrastructure.
- **Follow-ups:** dashboards and alert rules are delivered with Phase 1; log
  aggregation backend and tail sampling are production concerns noted in the ROADMAP.
