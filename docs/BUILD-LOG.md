# Build Log — implementation order & status

A durable, version-controlled record of **the order in which the platform is built** and
**where it stands**. Complements [ROADMAP.md](ROADMAP.md): the roadmap is forward-looking
(features & phases); this log is the concrete implementation sequence and live status.

**Legend:** ✅ done · 🔄 in progress · ⬜ pending

## Guiding principles

- **Foundation first** — decisions (ADRs), contracts (Avro/OpenAPI), and requirements
  before code, so implementation has no open questions.
- **Runnable vertical slices** — build depth-first so there is always something that runs
  (ADR-0012), not horizontal layers that only work once everything is done.
- **Verify every step** — each item records how it was proven (build/test output), not
  just "done".
- **Incremental reactor** — the parent `pom.xml` `<modules>` lists only built modules;
  each service is re-enabled as it is built, so the build never breaks on missing POMs.

## Implementation order & status

| # | Step | Status | Verification / notes |
|---|------|:------:|----------------------|
| 0 | Repository structure scaffold (cloud-native layout) | ✅ | Folder tree + `.gitignore` (secrets blocked, verified with `git check-ignore`) |
| 1 | Foundation docs — 18 ADRs + NFR/SLO | ✅ | Reviewed across 3 independent architect passes |
| 2 | Requirements spec + scenario catalog (S-1..S-18) | ✅ | Given/When/Then; FR→scenario→ADR traceability |
| 3 | Event/command catalog + Avro envelope | ✅ | `messageId`+`messageKind`; commands vs events |
| 4 | REST API guide + OpenAPI 3.1 | ✅ | Spec parses; all `$ref`s resolve; security-hardened |
| 5 | C4 + saga state machine + sequence diagrams | ✅ | Mermaid, GitHub-native |
| 6 | Root scaffolding — parent POM, README, Makefile, LICENSE, SECURITY.md, mvnw | ✅ | `./mvnw -N validate` = 0; Boot 4.1.1 resolves |
| 7 | Shared contracts — `avro-schemas` + `common-lib` | ✅ | `./mvnw install` = 0; 18 schemas → 22 classes; MoneyTest 5/5 |
| 8 | **order-service** — first runnable slice (REST + saga + outbox + Flyway + tests) | ✅ | `DOCKER_AVAILABLE=true ./mvnw clean -pl services/order-service -am verify` = **BUILD SUCCESS**. **25 unit tests** + **8 integration tests** (OrderPlacementIT 7 + context 1) pass over **real Postgres 17.6 + Kafka 4.1.2** (Testcontainers 2.0.5). Async saga end-to-end verified: happy path→CONFIRMED, payment-decline→compensation→CANCELLED, idempotency (409 on conflict), ownership 404, compensated saga→DONE. Added: Kafka DLQ+retry error handler w/ classification (ADR-0006), timeout sweeper (ADR-0003), 400 problem+json handlers, StockReleased consumer, traceparent. **Fixed critical latent bug: `@KafkaListener` was inactive** (Boot 4 needs `spring-boot-starter-kafka`, not bare `spring-kafka`) — reply events were consumed by nobody |
| 9 | payment-service + inventory-service (saga participants) | ✅ | Whole-reactor `DOCKER_AVAILABLE=true ./mvnw clean verify` = **BUILD SUCCESS**, **94 tests** (12 shared + 33 order + 42 payment + 37 inventory), 0 failures. payment: ADR-0016 intent/attempt/operation + providerIdempotencyKey + capture-once + UNKNOWN→reconciliation (new additive PaymentCaptureUnknown event). inventory: ADR-0015 atomic conditional decrement (no oversell, concurrent last-unit test) + TTL + Redis display-only. Additive shared: EnvelopeCodec/PlatformTopics/MessageTypes/TraceparentContext (order-service unchanged, still 25+8). |
| 10 | api-gateway + auth-service (Keycloak, ADR-0018) | ✅ | Whole-reactor `DOCKER_AVAILABLE=true ./mvnw clean verify` = **BUILD SUCCESS**, **149 tests**, 0 failures. api-gateway = Spring-native servlet reverse proxy (routing, edge JWT validation, correlation propagation, rate-limit 429, RFC 9457) — **Spring Cloud Gateway rejected on evidence** (no release train targets Boot 4.1.x; newest pins Boot 4.0.8). auth = **Keycloak** realm export + compose import (not a Java module). order-service now an OAuth2 resource server: **X-User-Id dev stand-in replaced by JWT `sub`** (RS256/JWKS/issuer/audience), 25 unit + **14 IT** (+6 JWT security tests). Additive shared: ProblemJson, PlatformScopes, PlatformJwtDecoders (spring-security-oauth2-jose optional → payment/inventory untouched). |
| 11 | One-command demo (compose) + CI pipeline | ✅ | `make up` brings up 8 containers (postgres 17.6 schema-per-service, kafka 4.1.2 KRaft, redis, keycloak+realm, 4 JVM services from Dockerfiles, readiness healthchecks). `make demo` **verified end-to-end**: happy order → CONFIRMED and pi_decline order → CANCELLED/PAYMENT_DECLINED, both through the gateway with a real Keycloak JWT across real containers (confirmed in DB + logs). CI: whole-reactor build + unit + IT (DOCKER_AVAILABLE=true), Java 21, CycloneDX SBOM, Trivy scan. Whole reactor **168 tests** green. Also fixed a pre-existing PaymentSagaIT flake. |
| 12 | Phase 1 review with user, then first push to GitHub | 🔄 | All 5 services built + demo runs E2E. Awaiting user review before first push. |

## Phase mapping (see ROADMAP)

- **Phase 1** = steps 6–12 (gateway + auth + order + payment + inventory, fully runnable).
- **Phase 2** = notification-service (MongoDB).
- **Phase 3** = audit-service (Cassandra) + ai-service (Spring AI + pgvector).

## Notes / decisions log

- Stack: Spring Boot 4.1.1 + Java 21 + Maven multi-module (verified in Maven Central).
- Group ID / package root: **`com.sajidriaz.orderplatform`** (group = package root, consistent
  across all modules — deliberately not split).
- Auth engine: **Keycloak** (ADR-0018) — services stay engine-agnostic (validate JWT via JWKS).
- Unit tests (`*Test`, Surefire) run everywhere; integration tests (`*IT`, Failsafe +
  Testcontainers 2.0.5) run over real Postgres 17.6 + Kafka 4.1.2 — **verified green in this
  environment** (fix: Testcontainers 1.x→2.0.5 for modern-Docker client negotiation;
  RestAssured→Spring RestTestClient; Ryuk disabled durably).
- Nothing is pushed to GitHub until the Phase 1 slice is reviewed and approved (step 12).

### Follow-ups surfaced during order-service (to address later)
- ✅ **FIXED (commit 41f2db9): order-service persists+reuses participant ids.**
  `reservationId` / `paymentIntentId` / `paymentAttemptId` are now generated once, stored on
  `saga_instance` (V2 migration + entity columns), and reused by ReleaseStock / CapturePayment
  — no longer random per command. (Was: order-service minted fresh random ids that matched
  nothing the participants issued; they correlated on `aggregateId` as a workaround.)
- ✅ **FIXED (commit 41f2db9): removed the shadowing `src/test/resources/application.yml`.**
  order-service ITs now run against the real main `application.yml` (dynamic properties
  override only container-specifics), instead of a stub that silently disabled it.
- ✅ **FIXED (commit 81d7a21): unpinned `spring-kafka` in the parent pom.** The Boot 4.1.1
  BOM's managed version (4.1.1) now applies platform-wide; per-service 4.1.1 overrides
  removed. (Was: parent pinned 3.2.4 built for Spring 6, breaking @KafkaListener on Boot 4.)
- **Blocking retries** (DefaultErrorHandler) implemented now; ADR-0014 non-blocking retry
  topics (`-retry-1s/-10s/-1m`) recorded as a follow-up.
- **REQUIRES_RECONCILIATION deferred to payment-service** (needs a `PaymentCaptureUnknown`
  Avro event + ADR-0016 reconciliation loop). order-service sweeper does the safe half
  (never confirms/cancels at pivot).
- **Doc fixes (non-fenced, do later):** REST-API-GUIDE 409-vs-422 self-contradiction (409 is
  correct); `X-User-Id` not sanctioned by any ADR (dev stand-in, gateway resolves);
  `PaymentCaptureFailed` missing from EVENT-CATALOG; saga-state-machine diagram state names
  don't match the `SagaStatus` enum.

_Last updated: after step 8 fully verified (order-service saga proven end-to-end over real
Postgres+Kafka; critical inactive-@KafkaListener bug fixed). Steps 9–12 remain._
