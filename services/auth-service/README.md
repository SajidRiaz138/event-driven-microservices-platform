# auth-service — Keycloak (identity provider)

Authentication is provided by **Keycloak** ([ADR-0018](../../docs/adr/0018-auth-service-keycloak.md)),
not a hand-rolled OAuth2 server. This directory holds the version-controlled **realm export**
that Keycloak imports on startup (`start-dev --import-realm`); there is no Java module here.

- `realm/order-platform-realm.json` — realm `order-platform`: RS256 signing, an OIDC PKCE
  client for the public API, a confidential `order-service` client (future service-to-service
  `client_credentials`), the scopes `orders:read` / `orders:write` / `orders:write:any`, an
  audience mapper stamping `aud=order-platform`, and demo users.

Every service validates the JWTs Keycloak issues as its own OAuth2 resource server — signature
via JWKS (`kid` rotation), issuer, audience and expiry ([ADR-0009](../../docs/adr/0009-per-service-jwt-resource-server.md)).

## ⚠️ Credentials in the realm export are LOCAL-DEV-ONLY

The realm JSON contains deliberately non-secret, throwaway credentials so the one-command
demo works out of the box (`make up` / `make demo`):

| Item | Dev value | Purpose |
|---|---|---|
| `order-service` client secret | `local-dev-only-order-service-secret` | confidential client for local demo |
| demo user passwords | `demo-password`, `other-password` | the two demo users the scripts log in as |

**These unlock nothing real** — only a throwaway Keycloak container that exists while you run
the demo on your own machine. They are the same category as the `changeme-dev-only` database
passwords in `.env.example`.

**For any real/shared deployment:** generate fresh client secrets and user passwords, supply
them via a secrets manager (never commit them), and do not reuse the values in this file.
