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
| 13 | Helm charts + Kubernetes (minikube) deployment — ADR-0008 realised | ✅ | `deploy/helm`: **library chart** (`platform-lib`: Deployment/Service/ServiceAccount/HPA/probes/labels), **4 service charts** that are only a `values.yaml` + one-line templates, **umbrella** (`platform-umbrella`) adding in-cluster infra (postgres 17.6 schema-per-service via init ConfigMap, kafka 4.1.2 KRaft single broker, redis, keycloak + realm ConfigMap) and a `values-minikube.yaml`. Realm JSON and init SQL are fed with `--set-file` from the files compose already uses — no second copy to drift. **Proven on a real cluster**: `minikube -p edmp` (8 GiB/4 CPU), 8/8 pods Ready, `make demo` through a port-forwarded gateway → order **CONFIRMED** and `pi_decline` order → **CANCELLED/PAYMENT_DECLINED**, one `correlationId` traced across all 4 pods. Measured footprint ≈3.2 GiB (4 GiB floor, 6 GiB comfortable). Runbook: [deployment/kubernetes-minikube.md](deployment/kubernetes-minikube.md). Fixed while doing it: correlationId was minted fresh by the saga instead of adopting the request's (so the id returned to the client appeared nowhere else), `SagaReplyListener` never restored it on Kafka replies, no service printed MDC values at all (ADR-0013 and `demo.sh` both already promised otherwise), actuator probe groups depended on a container env var, and `.gitignore`'s `*secret*` rule silently un-tracked the chart's Secret template. Whole reactor **169 tests** green (168 + a new IT asserting the envelope's correlationId equals the `X-Correlation-Id` response header). |

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

### Design polish (post-Phase-1)
- ✅ **ADR-0007's "schema AND role per service" is now true.** order/payment/inventory each
  authenticate as their own least-privilege Postgres role (`order_svc`, `payment_svc`,
  `inventory_svc`) instead of the shared `appuser`, provisioned by
  `deploy/local/postgres-init/02-service-roles.sh` — one file, used by both the compose stack
  and the Helm `postgres-init` ConfigMap. Each role is granted only its own schema; verified
  that Flyway applies under each restricted role and that all six cross-schema reads are
  refused. order-service deliberately stays on `public` (its V1 resolves `uuid_generate_v4()`
  column DEFAULTs at `CREATE TABLE` time, so scoping the connection to an `order` schema would
  put the extension out of `search_path` and fail the migration). `appuser` remains the
  bootstrap/admin role that no service uses.
- ✅ **Readiness health group is explicit: `readinessState` only — `db` and Kafka stay out.**
  Gating readiness on a dependency makes Kubernetes pull every replica from its Service on one
  Postgres blip, turning a recoverable hiccup into an outage; app-level resilience (consumer
  retries, transactional outbox, idempotent replay) is what handles transient dependency
  failure, so readiness means "this application is up and able to serve", not "all dependencies
  are healthy". This was previously only Spring Boot's default grouping — nothing stated the
  invariant, so a framework default could have changed it silently. Liveness unchanged; the
  aggregate `/actuator/health` still reports every indicator for humans.
- **REQUIRES_RECONCILIATION deferred to payment-service** (needs a `PaymentCaptureUnknown`
  Avro event + ADR-0016 reconciliation loop). order-service sweeper does the safe half
  (never confirms/cancels at pivot).
- **Doc fixes (non-fenced, do later):** REST-API-GUIDE 409-vs-422 self-contradiction (409 is
  correct); `X-User-Id` not sanctioned by any ADR (dev stand-in, gateway resolves);
  `PaymentCaptureFailed` missing from EVENT-CATALOG; saga-state-machine diagram state names
  don't match the `SagaStatus` enum.

_Last updated: after step 8 fully verified (order-service saga proven end-to-end over real
Postgres+Kafka; critical inactive-@KafkaListener bug fixed). Steps 9–12 remain._
