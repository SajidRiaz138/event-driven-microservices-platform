-- Order-service schema (ADR-0007: schema-per-service, one shared dev Postgres instance).
-- Tables owned exclusively by order-service: orders, order_lines, saga_instance,
-- outbox (ADR-0004), processed_message (ADR-0005 consumer dedup),
-- idempotency_key (ADR-0005 edge idempotency).

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- orders: the customer-facing aggregate. status mirrors the *public* view
-- (PENDING|CONFIRMED|CANCELLED) while the saga_instance table tracks the finer
-- internal SagaStatus states (ADR-0003). Default must be a valid SagaStatus name.
CREATE TABLE orders (
    id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id            VARCHAR(255)  NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    total_minor_units       BIGINT        NOT NULL,
    status                  VARCHAR(40)   NOT NULL DEFAULT 'PENDING',
    cancellation_reason     VARCHAR(40),
    payment_instrument_id   VARCHAR(128)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON COLUMN orders.status IS 'Public/internal saga status name (SagaStatus enum). Must never default to a non-SagaStatus value.';

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);

-- order_lines: server-side price snapshot per SKU (clients never send prices).
CREATE TABLE order_lines (
    id                       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id                 UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku                      VARCHAR(64)   NOT NULL,
    quantity                 INTEGER       NOT NULL,
    unit_price_minor_units   BIGINT        NOT NULL,
    currency                 VARCHAR(3)    NOT NULL
);

CREATE INDEX idx_order_lines_order_id ON order_lines (order_id);

-- saga_instance: persisted saga state (ADR-0003) — one row per order, never held
-- only in memory. current_step names the awaited command/reply; deadline drives
-- timeout-triggered retry/compensation (ADR-0014).
CREATE TABLE saga_instance (
    order_id         UUID PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    status           VARCHAR(40)  NOT NULL,
    current_step     VARCHAR(80),
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    deadline         TIMESTAMPTZ,
    correlation_id   UUID         NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lock_version     BIGINT       NOT NULL DEFAULT 0
);

-- outbox: transactional outbox (ADR-0004). Written in the same transaction as the
-- business change; a polling relay drains PENDING rows with SELECT ... FOR UPDATE
-- SKIP LOCKED and publishes them, marking SENT.
CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_id   UUID          NOT NULL,
    message_type   VARCHAR(160)  NOT NULL,
    message_kind   VARCHAR(10)   NOT NULL,
    topic          VARCHAR(160)  NOT NULL,
    payload        BYTEA         NOT NULL,
    headers        TEXT,
    status         VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_created_at ON outbox (status, created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox (aggregate_id);

-- processed_message: consumer-side dedup (ADR-0005). Written in the same DB
-- transaction as the business effect of consuming a reply event, so a redelivered
-- message is recognised and skipped.
CREATE TABLE processed_message (
    message_id      UUID          NOT NULL,
    consumer_group   VARCHAR(160)  NOT NULL,
    processed_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_group)
);

-- idempotency_key: edge (HTTP) idempotency (ADR-0005). Scope is customer + method +
-- path + key; written in the SAME transaction as the order it produced.
CREATE TABLE idempotency_key (
    key               VARCHAR(128)  NOT NULL,
    customer_id       VARCHAR(255)  NOT NULL,
    method            VARCHAR(10)   NOT NULL,
    path              VARCHAR(255)  NOT NULL,
    request_hash      VARCHAR(128)  NOT NULL,
    response_status   INTEGER       NOT NULL,
    response_body     TEXT          NOT NULL,
    order_id          UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (key, customer_id, method, path)
);
