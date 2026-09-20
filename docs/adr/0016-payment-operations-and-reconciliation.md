# ADR-0016: Payment operations model & unknown-state reconciliation

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

Payment is the one place in the platform where a lost response is not merely
inconvenient but financially dangerous. The critical failure window: **the provider
captures the funds, but the response is lost** (timeout, crash, network partition)
before payment-service records it. A naive retry then issues a *second* capture — a
double charge. Equally, treating a timeout as failure and refunding could refund a
capture that never happened. The result of a capture must be treated as **UNKNOWN**
until positively established, never assumed.

A second modelling error: using `UNIQUE(order_id)` as payment deduplication. That
forbids legitimate multiple attempts (a declined card retried with another) and split
payments, and conflates "an order" with "a payment operation."

## Decision

### 1. Model payment operations as first-class entities

```
payment_intent      one per order  — the intent to be paid (amount, currency, order_id)
  └── payment_attempt   one per try  — a distinct attempt (e.g. a card), may fail & be retried
        └── payment_operation  authorize | capture | refund | void
              - has a stable operationId (ours) and a providerIdempotencyKey (sent to provider)
              - status: PENDING | SUCCEEDED | FAILED | UNKNOWN
```

- Deduplication is on **`operationId` / `providerIdempotencyKey`**, never on `order_id`.
- Every call to the provider carries the **same `providerIdempotencyKey`** for that
  operation, so the provider itself collapses our retries into one effect.

### 2. Capture exactly once; UNKNOWN is a state, not a retry

- Before issuing a capture, the service checks for an existing `capture` operation for
  the attempt. If one exists and is `SUCCEEDED`, it is reused (no second capture).
- On timeout/lost response, the operation is recorded `UNKNOWN` — **not** FAILED,
  **not** SUCCEEDED.
- An `UNKNOWN` operation is resolved by **reconciliation**: query the provider (by our
  `providerIdempotencyKey` / provider ref) to learn the true outcome, then transition
  to `SUCCEEDED` or `FAILED`. Retrying the *capture* is only ever done with the same
  idempotency key, so it cannot double-charge.
- **Refund only when capture is positively established as SUCCEEDED** and the business
  decision (saga compensation after the pivot) requires it.

### 3. Effect on the saga

- The saga's capture step can resolve to `SUCCEEDED` → confirm, `FAILED` → compensate,
  or `UNKNOWN` → the order enters **`REQUIRES_RECONCILIATION`** (a non-terminal holding
  state) rather than being force-confirmed or force-cancelled. A reconciliation
  worker/alerts resolve it; the saga proceeds once the true outcome is known.
- This makes the earlier statement "confirmed only after capture" precise: an order may
  become `CONFIRMED` only after capture is **durably established as succeeded**; a
  timeout or unknown provider response must **not** be treated as success.

### 4. Security & data

- No raw PAN is ever stored — only an opaque provider `paymentMethodToken` (ties to
  ADR-0009). Operation records store amounts (Money type), statuses, provider refs, and
  idempotency keys — never card data.

## Consequences

- **Positive:** Double charges are impossible even across lost responses; multiple
  attempts and split payments are representable; refunds cannot fire against a
  non-existent capture; the dangerous "timeout == success/failure" assumption is
  eliminated.
- **Negative:** More entities and a reconciliation path to build and test (scenario
  S-17). Accepted — this is exactly the correctness that separates a real payment flow
  from a demo.
- **Related:** ADR-0003 (saga, REQUIRES_RECONCILIATION), ADR-0005 (idempotency),
  ADR-0014 (retry — capture UNKNOWN is reconciliation, not ordinary retry).
