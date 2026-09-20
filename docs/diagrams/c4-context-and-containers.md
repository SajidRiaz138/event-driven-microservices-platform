# C4 Diagrams — Event-Driven Microservices Platform

[C4 model](https://c4model.com/) views of the platform, at Context and Container levels.
Diagrams use Mermaid so they render directly on GitHub. Phase 1 scope is drawn solid;
Phase 2/3 services are shown dashed as planned.

## Level 1 — System Context

```mermaid
C4Context
    title System Context — Order Platform
    Person(customer, "Customer", "Places and tracks orders")
    System(platform, "Order Platform", "Event-driven microservices: order, payment, inventory, auth (Phase 1)")
    System_Ext(payProvider, "Payment Provider", "External payment gateway (tokenized)")
    System_Ext(idp, "Identity Provider", "OAuth2/OIDC issuer (auth-service / Keycloak)")

    Rel(customer, platform, "Places orders, checks status", "HTTPS/REST")
    Rel(platform, payProvider, "Authorize / capture / refund", "HTTPS")
    Rel(customer, idp, "Authenticates", "OAuth2/OIDC")
    Rel(platform, idp, "Validates JWTs (JWKS)", "HTTPS")
```

## Level 2 — Containers

```mermaid
flowchart TB
    customer([Customer])

    subgraph edge[Edge]
        gw["API Gateway<br/>(Spring Cloud Gateway)<br/>routing, JWT, rate-limit, idempotency-key"]
    end

    subgraph svcs[Services - Phase 1]
        auth["auth-service = Keycloak<br/>OAuth2/OIDC, JWT (RS256)<br/>realm import, no app DB"]
        order["order-service<br/>REST + SAGA ORCHESTRATOR + outbox<br/>(Postgres: order schema)"]
        pay["payment-service<br/>authorize/capture/refund + outbox<br/>(Postgres: payment schema)"]
        inv["inventory-service<br/>reservations (TTL) + outbox<br/>(Postgres: inventory schema) + Redis cache"]
    end

    subgraph infra[Infrastructure]
        kafka[("Kafka<br/>(Avro bytes, no registry)")]
        pg[("PostgreSQL<br/>schema + role per service")]
        redis[("Redis<br/>display-only cache")]
        obs["Observability<br/>Prometheus / Grafana / OTel"]
    end

    subgraph future[Phase 2/3 - planned]
        notif["notification-service<br/>(MongoDB)"]
        audit["audit-service<br/>(Cassandra)"]
        ai["ai-service<br/>(Spring AI + pgvector)"]
    end

    customer -->|HTTPS/REST| gw
    gw -->|JWT validated per-service| order
    gw --> auth
    order -->|commands / reply events| kafka
    pay <-->|commands / events| kafka
    inv <-->|commands / events| kafka
    order --> pg
    pay --> pg
    inv --> pg
    inv --> redis
    kafka -.->|events| notif
    kafka -.->|all events| audit
    order -.-> ai
    order & pay & inv & auth -.->|metrics/traces/logs| obs

    classDef planned stroke-dasharray: 5 5,fill:#f7f7f7,color:#666;
    class notif,audit,ai planned;
```

## Notes

- **Every service validates the caller's JWT itself** — the gateway is not a trust
  boundary (ADR-0009).
- **order-service hosts the saga orchestrator**; participants (payment, inventory)
  communicate via Kafka commands/reply events, **not** synchronous calls (ADR-0003).
- **Every producer has a transactional outbox** (ADR-0004).
- **Redis is display-only** and never backs a reservation decision (ADR-0007).
- Container-level detail (components inside a service) is added per service during the
  build phase.
