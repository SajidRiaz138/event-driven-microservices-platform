# ADR-0007: Polyglot persistence + one-Postgres, schema-per-service in dev

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Database-per-service is a core microservices principle: no service reaches into
another's tables. But "database-per-service" is a *logical* isolation rule, not a
mandate to run a separate database *server* per service — conflating the two makes a
platform impossible to run on a laptop, which is itself a failure (a reviewer who
cannot start it sees nothing).

We also want to demonstrate polyglot persistence *where the data model justifies it*,
not technology for its own sake.

## Decision

**Logical isolation, pragmatic deployment.**

- Each service **owns its schema** and never accesses another service's data directly
  — only via API or events.
- In **development**, all relational services share **one PostgreSQL instance** with a
  **separate schema and DB role per service** (`order`, `payment`, `inventory`,
  `auth`). Isolation is enforced by schema + role grants, not by separate servers.
- In **production**, a schema can be promoted to its own instance without code change
  (connection config only) — the isolation boundary is already correct.

Persistence choices for Phase 1 are all **PostgreSQL + Flyway**. Non-relational stores
are introduced later *only where justified*:

| Store | Where | Justification |
|---|---|---|
| PostgreSQL | order, payment, inventory, auth | Relational, transactional, ACID |
| Redis | inventory (cache), later ai | Distributed cache, display-only reads |
| pgvector | ai (Phase 3) | Vector search without adding a new engine |
| MongoDB | notification (Phase 2) | Flexible per-channel message documents — *if kept, with written argument* |
| Cassandra | audit (Phase 3) | Deferred; Kafka long-retention already serves as an audit log |

## Consequences

- **Positive:** The whole Phase 1 stack starts on a laptop; logical isolation is real;
  each store choice is defensible; production can split instances with no code change.
- **Negative:** A shared dev instance is a single point of failure in dev only
  (irrelevant) and requires disciplined schema/role separation (enforced in migrations).
