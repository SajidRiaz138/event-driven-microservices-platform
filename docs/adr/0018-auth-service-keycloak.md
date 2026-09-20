# ADR-0018: Auth-service engine — Keycloak

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The platform needs an OAuth2/OIDC authorization server to issue and manage tokens. Our
security model (ADR-0009) already fixes the *consumption* side: every service validates
JWTs (RS256/ES256) against a JWKS endpoint. What remained open was the *issuer*: build a
bespoke server (Spring Authorization Server) or adopt a proven IdP (Keycloak).

An OAuth2 authorization server is a large, security-critical surface: token issuance,
refresh rotation with reuse detection, PKCE, JWKS with `kid` rotation, revocation,
discovery. Mistakes here are security vulnerabilities, not ordinary bugs.

## Decision

Use **Keycloak** as the auth-service engine for the flagship.

- Keycloak runs as one container in the local stack; the platform realm (clients,
  scopes `orders:read`/`orders:write`/`orders:write:any`, users, roles) is imported from
  a version-controlled **realm export JSON** — reproducible with `make up`.
- Keycloak exposes the OIDC discovery + **JWKS** endpoints our services already expect
  (ADR-0009). Services are decoupled from the engine: they validate `RS256` tokens from a
  configured `issuer-uri`; that the issuer is Keycloak is a deployment detail.
- Flows: **Authorization Code + PKCE** for users; **client_credentials** for
  service-to-service (ADR-0009 §service-to-service).

### Why Keycloak over a bespoke Spring Authorization Server

- **Don't hand-roll auth.** Keycloak has already solved the subtle, dangerous parts
  correctly; a partially-correct custom server is worse than none.
- **The right signal.** Integrating a proven IdP correctly demonstrates senior judgment
  (integration maturity), whereas reinventing an IdP demonstrates the opposite. The Java
  depth in this portfolio is shown where it matters — the saga, outbox, payment
  reconciliation, concurrency — not in re-implementing OAuth2.
- **Realistic & runnable.** Real organisations run Keycloak/Auth0/Okta/Cognito; one
  container + a realm import gives reviewers working auth immediately.

## Consequences

- **Positive:** Correct, standard OIDC with minimal custom code; services stay
  engine-agnostic; the demo has real auth out of the box; the last open architectural
  decision is closed.
- **Negative:** Keycloak is a heavier dependency (JVM, ~hundreds of MB) than a minimal
  custom server — acceptable for a dev demo. The auth-service module is mostly
  configuration (realm export) rather than Java.
- **Optional companion:** a small Spring Authorization Server example may live in a
  clearly-labelled *learning/reference* repo to show the mechanics under the hood, while
  the flagship uses Keycloak for real.
- **Related:** ADR-0009 (per-service JWT validation), REST-API-GUIDE §6.
