-- Provisions ONE database with its two roles and its connect isolation
-- (docs/ARCHITECTURE.md §6, "Roles and permissions"). Idempotent.
--
-- bootstrap.sh runs it once for the main database and once for the key
-- database. Nothing here assumes both live on the same server: to host the
-- key database elsewhere, run this file on that instance as a superuser:
--
--   SCANOVA_PG_DB=… SCANOVA_PG_MIGRATOR=… SCANOVA_PG_MIGRATOR_PASSWORD=… \
--   SCANOVA_PG_APP=… SCANOVA_PG_APP_PASSWORD=… \
--   psql -X -v ON_ERROR_STOP=1 -d postgres -f provision-database.sql
--
-- Inputs come from the environment so that no password appears in a process
-- argument list. Table-level privileges are NOT granted here: the server's
-- migrate step applies them after its migrations.

\set ON_ERROR_STOP on
\getenv db SCANOVA_PG_DB
\getenv migrator SCANOVA_PG_MIGRATOR
\getenv migrator_password SCANOVA_PG_MIGRATOR_PASSWORD
\getenv app SCANOVA_PG_APP
\getenv app_password SCANOVA_PG_APP_PASSWORD

-- A failing statement must never put a password into the server log.
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
SET log_min_duration_statement = -1;

-- ── Roles ────────────────────────────────────────────────────────────
SELECT format('CREATE ROLE %I LOGIN', :'migrator')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator') \gexec

SELECT format('CREATE ROLE %I LOGIN', :'app')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app') \gexec

ALTER ROLE :"migrator" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'migrator_password';
ALTER ROLE :"app" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'app_password';

-- ── Database ─────────────────────────────────────────────────────────
SELECT format('CREATE DATABASE %I', :'db')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec

-- Connect isolation: nobody enters this database except its own two roles.
-- The migrator may also create schemas and trusted extensions in it.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"app";
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE :"db" TO :"migrator";

-- ── Schema `public` ──────────────────────────────────────────────────
\connect :"db"
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
SET log_min_duration_statement = -1;

-- The migrator owns the schema. The runtime role may look objects up in it
-- but cannot create any (no DDL).
ALTER SCHEMA public OWNER TO :"migrator";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM :"app";
GRANT USAGE ON SCHEMA public TO :"app";
