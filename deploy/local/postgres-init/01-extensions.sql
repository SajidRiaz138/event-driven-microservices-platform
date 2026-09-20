-- Runs once, as the bootstrap superuser, before any service connects
-- (postgres image executes /docker-entrypoint-initdb.d/*.sql on first init of the volume).
--
-- order-service's V1__Create_order_tables.sql starts with
--     CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- which only succeeds for a role privileged enough to install it. Installing it here, as the
-- bootstrap superuser, is what lets order-service run Flyway as an unprivileged role
-- (order_svc) that has no extension-install rights of its own: PostgreSQL checks for a
-- duplicate extension BEFORE it checks privileges, so once the extension exists the IF NOT
-- EXISTS in the migration is a NOTICE and not a permission error. Verified against 17.6.
--
-- It is installed into `public`, which is also where order-service's tables live, so the
-- uuid_generate_v4() column defaults in V1 resolve when CREATE TABLE parses them.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Roles, schemas and grants are in 02-service-roles.sh, which runs next. They are not here
-- because the role passwords come from the environment and a .sql file cannot read it.
