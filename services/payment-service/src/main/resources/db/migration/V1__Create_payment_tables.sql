-- payment-service schema (ADR-0007: schema-per-service; Flyway owns all DDL).
-- The payment operations model of ADR-0016:
--   payment_intent    one per order — the intent to be paid
--     payment_attempt   one per try  — a distinct attempt (e.g. another card)
--       payment_operation authorize | capture | refund | void, each with a stable operationId
--                         (ours) and a providerIdempotencyKey (sent to the provider)
--
-- Every object is explicitly schema-qualified and the schema is created here rather than left to
-- `spring.flyway.schemas` alone: configuration-driven schema placement fails silently — if the
-- property does not take effect, Flyway migrates `public` instead and nothing complains. The JPA
-- entities and native queries state the schema explicitly for the same reason.
--
-- NO CARD DATA IS STORED ANYWHERE IN THIS SCHEMA (ADR-0016 §4, ADR-0009). The only instrument
-- reference is an opaque provider token. There is deliberately no column a PAN could go in.
CREATE SCHEMA IF NOT EXISTS payment;

-- payment_intent: one per order. UNIQUE(order_id) here expresses exactly that, and is NOT
-- payment deduplication — see the note on payment_operation below, which is where ADR-0016's
-- "never dedup on order_id" rule applies.
CREATE TABLE payment.payment_intent (
    id                     UUID          PRIMARY KEY,
    order_id               UUID          NOT NULL UNIQUE,
    customer_id            UUID          NOT NULL,
    amount_minor_units     BIGINT        NOT NULL CHECK (amount_minor_units >= 0),
    currency               VARCHAR(3)    NOT NULL,
    -- Opaque provider token, never a PAN.
    payment_method_token   VARCHAR(255)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON COLUMN payment.payment_intent.payment_method_token IS
    'Opaque provider token. Never a raw PAN or any card data (ADR-0009, ADR-0016).';

-- payment_attempt: one per try. Several attempts per intent are legitimate — a declined card
-- retried with another instrument, or a split payment — which is precisely why deduplication
-- cannot live at the order level.
CREATE TABLE payment.payment_attempt (
    id                     UUID          PRIMARY KEY,
    intent_id              UUID          NOT NULL REFERENCES payment.payment_intent (id),
    payment_method_token   VARCHAR(255)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_attempt_intent_id ON payment.payment_attempt (intent_id);

-- payment_operation: one row per call to the provider.
--
-- status semantics (ADR-0016 §2), and the UNKNOWN state is the whole point of this table:
--   PENDING   — recorded, provider not yet answered
--   SUCCEEDED — positively established as done
--   FAILED    — positively established as not done
--   UNKNOWN   — the provider may or may not have acted; the response was lost. NOT a failure and
--               NOT a success. Only reconciliation may move it out of this state. Treating a
--               timeout as failure risks refunding a capture that never happened; treating it as
--               success risks confirming an order nobody paid for.
--
-- Deduplication is on operation_id (the primary key, ours) and provider_idempotency_key (sent to
-- the provider on every call for this operation, so the provider collapses our retries into one
-- effect). There is deliberately NO unique constraint on any order id in this table.
CREATE TABLE payment.payment_operation (
    id                          UUID          PRIMARY KEY,
    attempt_id                  UUID          NOT NULL REFERENCES payment.payment_attempt (id),
    operation_type              VARCHAR(16)   NOT NULL
        CHECK (operation_type IN ('AUTHORIZE', 'CAPTURE', 'REFUND', 'VOID')),
    status                      VARCHAR(16)   NOT NULL
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    -- The same key is reused for every retry of this operation. That is what makes a double
    -- charge structurally impossible rather than merely unlikely.
    provider_idempotency_key    VARCHAR(255)  NOT NULL UNIQUE,
    provider_reference          VARCHAR(255),
    amount_minor_units          BIGINT        NOT NULL CHECK (amount_minor_units >= 0),
    currency                    VARCHAR(3)    NOT NULL,
    failure_reason              VARCHAR(255),
    -- How many times reconciliation has asked the provider about an UNKNOWN outcome. Counted so an
    -- operation that can never be resolved becomes visible instead of being polled forever.
    reconcile_attempts          INTEGER       NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version                BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_operation_attempt_id ON payment.payment_operation (attempt_id);

-- Capture-once, enforced by the database (ADR-0016 §2).
--
-- At most one capture per attempt may be live, where "live" means anything other than definitively
-- FAILED. A second concurrent capture cannot be inserted, so the application's check-then-capture
-- cannot be defeated by a race. A definitively failed capture is excluded so a legitimate retry
-- remains possible.
CREATE UNIQUE INDEX uq_payment_operation_one_live_capture_per_attempt
    ON payment.payment_operation (attempt_id)
    WHERE operation_type = 'CAPTURE' AND status <> 'FAILED';

-- The reconciliation worker's claim query reads exactly this set.
CREATE INDEX idx_payment_operation_unknown
    ON payment.payment_operation (updated_at)
    WHERE status = 'UNKNOWN';

-- outbox: transactional outbox (ADR-0004), identical in shape to the other services'.
CREATE TABLE payment.outbox (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id   UUID          NOT NULL,
    message_type   VARCHAR(160)  NOT NULL,
    message_kind   VARCHAR(10)   NOT NULL,
    topic          VARCHAR(160)  NOT NULL,
    payload        BYTEA         NOT NULL,
    headers        TEXT,
    status         VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempt_count  INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_created_at ON payment.outbox (status, created_at);
CREATE INDEX idx_outbox_aggregate_id ON payment.outbox (aggregate_id);

-- processed_message: consumer-side dedup (ADR-0005 layer 2), written in the same transaction as
-- the payment state change.
CREATE TABLE payment.processed_message (
    message_id      UUID          NOT NULL,
    consumer_group  VARCHAR(160)  NOT NULL,
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_group)
);
