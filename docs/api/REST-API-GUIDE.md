# REST API Guide — Order Platform (Phase 1)

The public HTTP contract, its conventions, and per-endpoint documentation. The
machine-readable source of truth is the OpenAPI 3.1 spec in
[`../../shared/openapi/order-service.yaml`](../../shared/openapi/order-service.yaml).

---

## 1. Conventions

### Base URL & versioning
- All endpoints are versioned in the path: `/api/v1/...`.
- Breaking changes bump the major version (`/api/v2`); additive changes do not.

### Media types
- Requests/responses: `application/json`.
- Business errors: `application/problem+json` (RFC 9457, §4). **Exception:** the OAuth2
  token endpoint follows the OAuth2 protocol contract and returns OAuth error bodies
  (`{ "error": "invalid_grant", ... }`), not `problem+json` (§6).

### Authentication & identity
- **Bearer JWT** in `Authorization: Bearer <token>`, validated at the gateway **and**
  independently by each service (ADR-0009).
- **Customer identity is derived from the JWT (`sub`), never from the request body.**
  Clients cannot place orders as another customer. An administrator placing an order on
  behalf of a customer requires the distinct scope **`orders:write:any`** and the action
  is audited as impersonation.
- Customer scopes: `orders:write` (place own), `orders:read` (view own),
  `orders:write:any` (place for another — admin, audited).

### Pricing (server-authoritative)
- **Clients never send prices.** The request contains only `sku` + `quantity`. The
  order-service resolves the authoritative unit price server-side (from a catalog or a
  server-issued **`quoteId`**) and persists a **price snapshot** on the order. This
  prevents client price tampering.

### Idempotency
- **Business mutation endpoints** (e.g. place order) require an **`Idempotency-Key`**
  (client-generated UUID). Authentication/token endpoints follow their own
  protocol-specific contract and are **not** subject to this rule.
- **Scope:** `customer (JWT sub) + method + path + key`. Max key length 128 chars.
  Keys retained ≥ 24h.
- **Semantics:**
  - same key + same request hash → the **original** response is replayed (including the
    original `202` and order id, even after the order has progressed);
  - same key + **different** request hash → `409 Conflict`;
  - a concurrent in-flight request with the same key → the current order/status is
    returned (no second order).
- The idempotency record (key, request hash, order id, original response) is written
  **transactionally** with the order (order-service is authoritative; the gateway may
  pre-enforce basic rules).

### Asynchronous operations
- Order placement is async: valid requests return **`202 Accepted`** + `Location`
  (status resource). **Read-your-writes guarantee:** the order row is committed
  *before* the `202` is returned, so a subsequent authenticated `GET` with the returned
  order id **never** returns `404` for that order, even before the saga starts. Business
  outcomes (insufficient stock, payment declined) are **not** HTTP errors — they surface
  as the order's terminal `status`/`reason` (§3, §5).

### Pagination (cursor-based)
- Collections use **cursor pagination**: `GET ...?limit=20&cursor=<opaque>`.
- Response: `{ "items": [...], "nextCursor": "<opaque|null>" }`. `limit` default 20,
  max 100. Sort order is stable (createdAt, id); a null `nextCursor` means the end.

### Rate limiting
- `429 Too Many Requests` + `Retry-After` (seconds) when a client exceeds its quota.

### Correlation & tracing (see also ADR-0013)
- `traceparent` (request, optional) carries **W3C distributed-tracing** context; if
  absent it is generated at the edge.
- `X-Correlation-Id` is the **business/support** correlation identifier, present on
  **every** response (success and error) and in the `problem+json` body. If the client
  supplies a valid one it is validated and propagated; otherwise one is generated. It
  appears on every log line, event, outbox record, and saga transition, and must never
  encode sensitive data.

### Standard headers
- Request: `Authorization`, `Idempotency-Key` (business mutations), optional
  `traceparent`, optional `X-Correlation-Id`.
- Response: `X-Correlation-Id` (always), `Location` (202), `Retry-After` (429/503),
  `X-RateLimit-*`.

## 2. Status codes

| Code | When |
|---|---|
| `200 OK` | Successful read |
| `202 Accepted` | Order **request** accepted & valid; processing async (status URL returned) |
| `400 Bad Request` | Malformed syntax / unparsable body |
| `401 Unauthorized` | Missing/expired/invalid token |
| `403 Forbidden` | Valid token, insufficient scope |
| `404 Not Found` | Resource missing, or not owned by caller (S-15) |
| `409 Conflict` | State conflict (e.g. cancel an already-confirmed order) **or `Idempotency-Key` reuse with a different request body** |
| `422 Unprocessable Entity` | Synchronous validation failure (empty lines, quantity ≤ 0, locally-unknown SKU, bad currency) |
| `429 Too Many Requests` | Rate limit exceeded |
| `503 Service Unavailable` | order-service cannot persist the request / shedding load |

**Synchronous validation vs asynchronous business outcome** — the key distinction:

| Condition | API result |
|---|---|
| Empty lines, invalid quantity, malformed currency, locally-unknown SKU | `422` immediately |
| Cannot persist the order request | `503` |
| **Insufficient stock** | `202`, order later `CANCELLED` (reason `INSUFFICIENT_STOCK`) |
| **Payment declined** | `202`, order later `CANCELLED` (reason `PAYMENT_DECLINED`) |
| Transient inventory/payment failure | `202`, saga retries |
| Capture outcome unknown | `202`, order → `REQUIRES_RECONCILIATION` internally (ADR-0016) |

## 3. Endpoints (Phase 1)

### `POST /api/v1/orders` — place an order
- **Auth:** `orders:write` · **Headers:** `Idempotency-Key` (required), optional `traceparent`
- **Request** (identity from JWT; no customerId, no prices; payment instrument by opaque reference):
  ```json
  {
    "paymentInstrumentId": "pi_9a3c...",
    "lines": [ { "sku": "SKU-1001", "quantity": 2 } ],
    "currency": "USD",
    "quoteId": "optional-server-issued-quote"
  }
  ```
  `paymentInstrumentId` is an opaque reference to a stored payment instrument (never raw
  card data). order-service resolves it to a provider `paymentMethodToken` server-side
  when issuing `AuthorizePayment`.
- **Responses:**
  - `202 Accepted` → `Location: /api/v1/orders/{orderId}`, `X-Correlation-Id`;
    body `{ "orderId", "status": "PENDING" }`
  - `400` malformed · `401` · `403` (missing scope) · `422` validation / idempotency-key
    payload conflict · `429` · `503` (cannot persist)
- **Scenarios:** S-1, S-5, S-7, S-13, S-14, S-16, S-18 (business failures S-2/S-3 are
  observed via status, not here)

### `GET /api/v1/orders/{orderId}` — get order status
- **Auth:** `orders:read` (caller must own the order)
- **Response `200`** (fully typed, with line totals):
  ```json
  {
    "orderId": "8b7...uuid",
    "status": "PENDING",
    "lines": [
      { "sku": "SKU-1001", "quantity": 2,
        "unitPrice": { "minorUnits": 1999, "currency": "USD" },
        "lineTotal": { "minorUnits": 3998, "currency": "USD" } }
    ],
    "totalAmount": { "minorUnits": 3998, "currency": "USD" },
    "reason": null,
    "createdAt": "2026-09-19T18:30:00Z",
    "updatedAt": "2026-09-19T18:30:02Z"
  }
  ```
  - `status ∈ { PENDING, CONFIRMED, CANCELLED }` (public view; the internal saga has
    finer states incl. `REQUIRES_RECONCILIATION`). **`PENDING` includes normal
    processing *and* payment reconciliation** — the public API deliberately does not
    expose internal saga states. Operators get the detailed state via a separate
    diagnostic view, not this customer endpoint.
  - `reason` (set when `CANCELLED`) ∈ `{ INSUFFICIENT_STOCK, PAYMENT_DECLINED,
    PAYMENT_CAPTURE_FAILED, ORDER_TIMEOUT, RECONCILIATION_REQUIRED }`.
  - Prices are the **server-persisted snapshot**, not client input.
  - `401` · `404` (missing **or** not owned — existence not revealed, S-15)

### `POST /api/v1/auth/token` — OAuth2 token endpoint (auth-service)
- Belongs to the **auth-service**, documented in its **own** OpenAPI document — it is
  intentionally **not** part of `order-service.yaml` (which is orders-only). It follows
  the **OAuth2 protocol contract** (§6), not the generic idempotency/error rules.

## 4. Payment correctness (public guarantee)

The public API does not expose payment internals, but guarantees:

> A timeout or lost response from the payment provider **does not create a new charge.**
> The payment service reconciles the original operation using the same
> `providerIdempotencyKey`, capturing at most once (ADR-0016).

Internally, payments are modelled as `paymentIntent → paymentAttempt → paymentOperation`
with a `providerIdempotencyKey` and states `PROCESSING | UNKNOWN | SUCCEEDED | FAILED`
— supporting retries, multiple attempts, and split/partial payments. Payments are
**never** deduplicated by `orderId` alone.

## 5. Error model — RFC 9457 (`application/problem+json`)

Business errors return a problem document. **Operational data is not leaked** (no stock
levels, no internal identifiers):

```json
{
  "type": "https://docs.platform.local/problems/validation-failed",
  "title": "Validation failed",
  "status": 422,
  "detail": "One or more order lines are invalid.",
  "instance": "/api/v1/orders",
  "correlationId": "7b1e...",
  "errors": [ { "field": "lines[0].quantity", "issue": "must be >= 1" } ]
}
```

- `correlationId` — always present, matches the `X-Correlation-Id` header, ties to
  logs/traces (ADR-0013).
- **Insufficient stock is not an HTTP error** in this async design — it appears as the
  order's `CANCELLED` status with `reason = INSUFFICIENT_STOCK`, and messages never
  reveal available quantities.
- **OAuth exception:** the token endpoint returns OAuth2 error bodies
  (`{ "error": "invalid_grant" }`), not `problem+json`.

## 6. OAuth2 / authentication contract

- **Engine:** to be finalised (Keycloak vs Spring Authorization Server, ADR pending);
  the *contract* below holds regardless.
- **Flows:**
  - **Authorization Code + PKCE** — interactive users (returns `access_token` +
    `refresh_token`).
  - **Client Credentials** — service-to-service (returns `access_token`, **no**
    `refresh_token`).
  - **Refresh Token** — only where a refresh token was issued.
- **Token properties:** fixed `issuer` + `audience`; **RS256/ES256** signing; **JWKS**
  endpoint with `kid` rotation; short access-token TTL (5–15 min); refresh rotation with
  reuse detection.
- **Routing:** token issuance via the auth-service route; resource APIs behind the
  gateway. Public endpoints: `/auth/token`, health, metrics.

## 7. Service-to-service authorization

Customer scopes are not sufficient for internal calls. Internal permissions use
**service identity** (`client_credentials`), not a forwarded customer JWT, e.g.:

| Caller → callee | Scope |
|---|---|
| order-service → inventory-service | `inventory:reserve`, `inventory:release` |
| order-service → payment-service | `payment:authorize`, `payment:capture`, `payment:refund` |
| payment-service → order-service | `order:payment-result` |

On-behalf-of a user (rare) propagates the user token or uses token exchange (ADR-0009).
Kafka messages carry no ambient identity — consumer authorization is re-derived from the
payload and enforced by topic ACLs.

## 8. Scenario → endpoint coverage

Every scenario in [REQUIREMENTS.md §4](../REQUIREMENTS.md) maps to an endpoint and a
response code (or a terminal order status, for async business outcomes); these pairs
become the API-level acceptance tests in the build phase.
