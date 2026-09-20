-- Runs once, as the bootstrap superuser, before any service connects
-- (postgres image executes /docker-entrypoint-initdb.d/*.sql on first init of the volume).
--
-- order-service's V1__Create_order_tables.sql starts with
--     CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- which only succeeds for a role privileged enough to install it. It happens to work as
-- appuser today because appuser owns orderdb and uuid-ossp is a trusted extension, but
-- that is an emergent property of the env wiring, not something the repo states. Doing it
-- here makes the requirement explicit and keeps Flyway working if the owning role changes.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Schemas are NOT created here on purpose: payment-service and inventory-service each run
-- CREATE SCHEMA IF NOT EXISTS <name> inside their own V1 migration (deliberate, see the
-- comments in those files), and order-service owns the public schema of orderdb.
