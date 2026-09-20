# Security Policy

## Reporting a vulnerability

This is a demonstration/portfolio project. If you find a security issue, please open a
GitHub issue marked **[security]**, or contact the maintainer directly rather than
disclosing publicly with exploit detail.

## Security posture (by design)

Security is treated as an architectural concern, not an afterthought. See
[ADR-0009](docs/adr/0009-per-service-jwt-resource-server.md) and
[REQUIREMENTS.md §8](docs/REQUIREMENTS.md) for the full model.

- **Authentication:** OAuth2/OIDC; JWTs signed with RS256/ES256, validated against a
  JWKS endpoint with `kid` rotation. Short-lived access tokens; refresh rotation with
  reuse detection.
- **Per-service validation:** every service is its own resource server and validates the
  token itself — no "confused deputy" trust of the gateway.
- **Authorization:** coarse scope checks at the edge; resource-level ownership checks in
  the owning service. Cross-customer access returns `404` (existence not revealed).
- **Service-to-service:** `client_credentials` (autonomous) vs propagated user token /
  token exchange (on-behalf-of). Kafka authz re-derived from payload + topic ACLs.
- **Secrets:** never committed. `.gitignore` blocks tokens/keys/`.env`/kubeconfigs;
  secrets are delivered via a secrets manager (roadmap: External Secrets / SOPS).
- **Payment data:** no raw card data (PAN) is ever stored — only opaque provider tokens
  ([ADR-0016](docs/adr/0016-payment-operations-and-reconciliation.md)).
- **Money:** integer minor units + currency, never floating point.
- **Error responses:** RFC 9457 problem details that never leak operational data,
  stack traces, SQL, or credentials.
- **Supply chain:** CI will run dependency and container image scanning + SBOM generation (planned in step 11 of BUILD-LOG.md).

## Supported versions

Phase 1 is under active development; only `main` is supported.
