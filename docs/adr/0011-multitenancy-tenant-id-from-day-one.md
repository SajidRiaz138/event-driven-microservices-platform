# ADR-0011: Carry tenantId from day one (default tenant)

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Full multi-tenancy (tenant isolation, per-tenant limits, data partitioning) is out of
scope and deferred. However, *retrofitting* a tenant identifier into event envelopes,
cache keys, and primary keys after the fact is brutal — it touches every schema,
every topic, and every cache. The cost asymmetry is stark: threading a `tenantId`
through now is a few minutes; adding it later is a rewrite.

## Decision

**Carry `tenantId` everywhere from the start, always set to `"default"`** until real
multi-tenancy is implemented.

- `tenantId` is a required field in the **event envelope** (ADR-0010).
- `tenantId` is a component of **cache keys** (`{tenant}:{entity}:{id}`).
- `tenantId` is part of the **primary/partition key** design where tenant isolation
  would eventually apply.

No tenant-resolution logic is built yet; a single constant `"default"` is used. The
*shape* is correct, so enabling multi-tenancy later is additive, not structural.

## Consequences

- **Positive:** The expensive structural change is already absorbed; future
  multi-tenancy becomes a feature, not a migration.
- **Negative:** A field that is constant today and looks redundant to a casual reader —
  mitigated by this ADR explaining why it exists.
