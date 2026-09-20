# ADR-0009: Per-service JWT validation (no confused deputy)

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The API gateway validates the caller's JWT at the edge. A tempting simplification is
to have the gateway strip the token and forward a plain `X-User-Id` header, letting
downstream services *trust* the gateway. That creates a **confused deputy**: anything
that can reach a service on the pod network is then implicitly authenticated, because
the service performs no verification of its own. In a distributed system the network
is not a trust boundary.

## Decision

**Every service is its own OAuth2 resource server.**

- Each service independently validates the JWT: **signature** (against the issuer's
  JWKS, honouring `kid` rotation), **issuer**, **audience**, and **expiry**. The
  gateway validates too (fail fast at the edge), but services never *rely* on it.
- The token is **propagated** to downstream calls; identity is not flattened into a
  trusted header.
- **Tokens are asymmetric (RS256/ES256)** so services verify with the public JWKS and
  never hold a shared secret. Access tokens are short-lived (5–15 min); refresh tokens
  rotate with reuse detection.
- **Service-to-service** auth distinguishes two cases:
  - *On behalf of a user* → propagate the user token (or RFC 8693 token exchange).
  - *Autonomous* (e.g. the outbox relay) → `client_credentials` with a per-service
    client and narrowly scoped grants.
- **Authorization is layered:** coarse scope checks at the edge; **resource-level
  ownership** checks in the owning service ("can this user read *this* order?" is a
  question only order-service can answer).
- **Kafka messages carry no ambient caller identity.** Consumer authorization is
  re-derived from the command payload; authenticity is enforced by **topic ACLs**
  restricting who may write each topic.

## Consequences

- **Positive:** No confused deputy; compromise of the pod network does not grant
  application access; authorization decisions are made where the data lives.
- **Negative:** JWT validation logic in every service (centralised in `common-lib`)
  and JWKS fetch/caching per service. Accepted as the correct security posture.
- **Deferred:** mTLS between services is best delivered by a service mesh (roadmap);
  until then the posture is NetworkPolicy + per-hop JWT, stated honestly.
