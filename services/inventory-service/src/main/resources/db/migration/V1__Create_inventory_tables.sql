-- inventory-service schema (ADR-0007: schema-per-service; Flyway owns all DDL).
-- Tables owned exclusively by inventory-service: stock_item, reservation, reservation_line,
-- outbox (ADR-0004), processed_message (ADR-0005 consumer dedup).
--
-- Every object is explicitly schema-qualified and the schema is created here rather than left to
-- `spring.flyway.schemas`/`create-schemas` alone. Configuration-driven schema placement fails
-- silently — if the property does not take effect, Flyway happily migrates `public` instead and
-- nothing complains until something else breaks. Qualifying the DDL makes the placement a
-- property of the migration itself, which cannot be misconfigured away. The JPA entities and the
-- native queries state the schema explicitly for the same reason.
--
-- Uses gen_random_uuid() (core since PostgreSQL 13, resolved from pg_catalog so it is unaffected
-- by search_path) rather than uuid-ossp's uuid_generate_v4(), which would need an extension
-- installed by a superuser.
CREATE SCHEMA IF NOT EXISTS inventory;

-- stock_item: the resource being protected. Three distinct quantities per ADR-0015:
--   on_hand  — physically held
--   reserved — held for in-flight orders (not yet sold)
--   available = on_hand - reserved (derived, never stored: a stored duplicate is a second
--               source of truth that can disagree with itself)
CREATE TABLE inventory.stock_item (
    sku            VARCHAR(64)  PRIMARY KEY,
    on_hand        INTEGER      NOT NULL CHECK (on_hand >= 0),
    reserved       INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    lock_version   BIGINT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The no-oversell invariant as a database constraint, not merely an application rule
    -- (ADR-0015, scenario S-6). The atomic conditional UPDATE in StockItemRepository is what
    -- makes reservation correct under concurrency; this CHECK is the backstop that makes
    -- oversell impossible even via a buggy future code path or a manual SQL statement.
    CONSTRAINT stock_item_no_oversell CHECK (reserved <= on_hand)
);

COMMENT ON COLUMN inventory.stock_item.reserved IS
    'Held for in-flight orders. available = on_hand - reserved; never let reserved exceed on_hand.';

-- reservation: the reservation aggregate, with a TTL so an abandoned reservation frees its
-- stock even if the orchestrator dies (ADR-0015).
--   ACTIVE    — holding stock, expires at expires_at
--   COMMITTED — the order was confirmed; stock is sold (on_hand decremented), TTL no longer applies
--   RELEASED  — released by a ReleaseStock command (saga compensation)
--   EXPIRED   — released by the TTL sweeper
CREATE TABLE inventory.reservation (
    id             UUID         PRIMARY KEY,
    order_id       UUID         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    -- Correlation id of the ReserveStock command that created this reservation. Stored so that
    -- an event emitted later with no inbound message to copy it from — notably a TTL expiry,
    -- which nobody asked for — still joins up with the order's flow in logs and traces
    -- (ADR-0013). Without it, expiry events would be orphans.
    correlation_id UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    lock_version   BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_reservation_order_id ON inventory.reservation (order_id);
CREATE INDEX idx_reservation_status_expires_at ON inventory.reservation (status, expires_at);

-- At most ONE active reservation per order, enforced by the database.
--
-- This matters more than it looks: the orchestrator currently mints a fresh random
-- reservationId on every ReserveStock (it does not persist the id this service returned), so
-- reservationId alone cannot be trusted to identify a retry. Without this index, a redelivered
-- or re-issued ReserveStock carrying a different reservationId would reserve the same stock a
-- second time. With it, the second insert fails outright.
CREATE UNIQUE INDEX uq_reservation_one_active_per_order
    ON inventory.reservation (order_id) WHERE status = 'ACTIVE';

CREATE TABLE inventory.reservation_line (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id   UUID         NOT NULL REFERENCES inventory.reservation (id) ON DELETE CASCADE,
    sku              VARCHAR(64)  NOT NULL,
    quantity         INTEGER      NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_line_reservation_id
    ON inventory.reservation_line (reservation_id);

-- outbox: transactional outbox (ADR-0004). Written in the SAME transaction as the stock
-- change it announces; the relay drains PENDING rows with SELECT ... FOR UPDATE SKIP LOCKED
-- and publishes them, marking SENT.
CREATE TABLE inventory.outbox (
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

CREATE INDEX idx_outbox_status_created_at ON inventory.outbox (status, created_at);
CREATE INDEX idx_outbox_aggregate_id ON inventory.outbox (aggregate_id);

-- processed_message: consumer-side dedup (ADR-0005 layer 2). Written in the same DB
-- transaction as the business effect, so a redelivered command is recognised and skipped.
CREATE TABLE inventory.processed_message (
    message_id      UUID          NOT NULL,
    consumer_group  VARCHAR(160)  NOT NULL,
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_group)
);

-- Dev/demo seed: the SKUs order-service's price catalog knows about, so the stack is
-- demonstrable end to end out of the box. Integration tests insert their own SKUs.
INSERT INTO inventory.stock_item (sku, on_hand, reserved) VALUES
    ('SKU-1001', 100, 0),
    ('SKU-1002', 100, 0);
