# One PostgreSQL login role per service, each granted only on its own schema — the second
# half of ADR-0007 ("a separate schema AND DB role per service"), which until now was only
# half true: all three services authenticated as the single bootstrap role `appuser`.
#
# Runs once, as the bootstrap superuser, on first initialization of the data directory,
# immediately after 01-extensions.sql. The same file backs both deployment paths: the
# compose stack bind-mounts this directory, and the Helm chart puts it in the postgres-init
# ConfigMap (--set-file), so the two environments cannot drift.
#
# Why a shell script and not plain SQL: the role passwords come from the environment
# (POSTGRES_ORDER_PASSWORD and friends), so nothing that looks like a credential is
# committed. A .sql file has no way to read the environment; psql's :'variable' does, and
# it interpolates as a properly quoted literal rather than by string concatenation.
#
# Source-safe on purpose. The postgres entrypoint EXECUTES *.sh here if the file carries the
# executable bit and otherwise SOURCES it, and a ConfigMap-mounted file is never executable.
# So this script must be harmless when sourced into the entrypoint's own shell: no `exit`
# (it would abort the entrypoint mid-initialization) and no `set -u`/`set -e` (they would
# leak). Failure still aborts initialization, which is what we want — a half-granted
# database is worse than none: ON_ERROR_STOP=1 makes psql exit non-zero and the entrypoint
# already runs under `set -e`.
#
# Role names are fixed here rather than read from the environment, because the GRANTs below
# have to name the same roles the services authenticate as. The services' POSTGRES_*_USER
# properties default to exactly these names; a real deployment provisions its roles
# externally and points those properties at them.

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v db="$POSTGRES_DB" \
	-v order_password="${POSTGRES_ORDER_PASSWORD:-changeme-dev-only}" \
	-v payment_password="${POSTGRES_PAYMENT_PASSWORD:-changeme-dev-only}" \
	-v inventory_password="${POSTGRES_INVENTORY_PASSWORD:-changeme-dev-only}" <<'EOSQL'

-- ---------------------------------------------------------------------------------------
-- The three login roles. CREATE ROLE has no IF NOT EXISTS, and this script is also the
-- documented way to retrofit the roles onto a database whose volume already exists (a
-- Kubernetes PVC survives helm uninstall, so initdb will not re-run for it), so guard the
-- creation and set the passwords separately. NOINHERIT is not needed: these roles are
-- members of nothing. No CREATEDB, no CREATEROLE, no SUPERUSER.
-- ---------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'order_svc') THEN
        CREATE ROLE order_svc LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_svc') THEN
        CREATE ROLE payment_svc LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_svc') THEN
        CREATE ROLE inventory_svc LOGIN;
    END IF;
END
$$;

-- Outside the DO block deliberately: psql does not interpolate variables inside
-- dollar-quoted strings.
ALTER ROLE order_svc     WITH PASSWORD :'order_password';
ALTER ROLE payment_svc   WITH PASSWORD :'payment_password';
ALTER ROLE inventory_svc WITH PASSWORD :'inventory_password';

-- ---------------------------------------------------------------------------------------
-- order-service keeps the `public` schema; order_svc gets it to itself.
--
-- The alternative — moving order-service to its own `order` schema for symmetry with
-- payment/inventory — was rejected as the higher-risk path, for a concrete reason rather
-- than caution: V1__Create_order_tables.sql declares uuid_generate_v4() column DEFAULTs,
-- and a DEFAULT expression is resolved when CREATE TABLE parses it. Scoping the connection
-- to an `order` schema (spring.datasource.hikari.schema, as payment/inventory do) sets
-- search_path to that schema alone, so uuid_generate_v4() — which lives in `public` with
-- the extension — would no longer resolve and V1 would fail at migration time. It would
-- also re-run both migrations into a new, empty flyway_schema_history on every existing
-- volume. Keeping `public` makes this change a pure grants change for order-service: no
-- migration, entity or query is touched.
--
-- PostgreSQL grants USAGE on `public` to PUBLIC by default, which would leave every role
-- able to resolve names in order-service's schema. Revoking it is what makes the boundary
-- real: verified on 17.6 that payment_svc then gets "permission denied for schema public".
-- CREATE lets Flyway create its own flyway_schema_history there.
-- ---------------------------------------------------------------------------------------
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO order_svc;

-- ---------------------------------------------------------------------------------------
-- payment/inventory: create the schema up front, owned by the service role. Ownership is
-- the grant — an owner has USAGE and CREATE implicitly and needs no further privilege
-- inside it — and it means the boundary exists before the service has ever connected,
-- rather than being a side effect of whichever role happened to run Flyway first.
-- No USAGE is granted to PUBLIC, so the other roles cannot resolve names in here either.
-- ---------------------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS payment   AUTHORIZATION payment_svc;
CREATE SCHEMA IF NOT EXISTS inventory AUTHORIZATION inventory_svc;

-- ---------------------------------------------------------------------------------------
-- The one privilege that is wider than "its own schema", and why it is unavoidable:
-- V1__Create_payment_tables.sql and V1__Create_inventory_tables.sql each open with
-- CREATE SCHEMA IF NOT EXISTS <name>, deliberately (their comments explain that
-- configuration-driven schema placement fails silently). PostgreSQL checks CREATE on the
-- database BEFORE it checks whether the schema already exists, so IF NOT EXISTS does not
-- make that statement a no-op for an unprivileged role — verified on 17.6, where it fails
-- with "permission denied for database orderdb" even though the schema is right there.
--
-- So the migrations as written require this grant. The alternative was editing an
-- already-applied V1, which changes its Flyway checksum and breaks validation on every
-- existing database — a far worse trade for a much smaller privilege. What this permits is
-- creating additional schemas; it grants no access to any schema that exists, so the
-- "a compromised service cannot read another service's data" property is unaffected.
--
-- order_svc is deliberately NOT granted this: its own migrations never create a schema.
-- ---------------------------------------------------------------------------------------
GRANT CREATE ON DATABASE :"db" TO payment_svc, inventory_svc;

EOSQL
