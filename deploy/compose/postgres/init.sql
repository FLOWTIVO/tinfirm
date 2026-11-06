-- Tinfirm roles and database for the local Compose stack. DEVELOPMENT ONLY.
--
-- Runs once, when the postgres container initialises an EMPTY data volume
-- (/docker-entrypoint-initdb.d). A volume that already exists is left alone; to re-run
-- this file, remove the volume: `make down`, then `docker volume rm tinfirm_postgres-data`.
--
-- Two roles (docs/CONVENTIONS.md §7):
--   tinfirm_owner  owns the database and every object in it; Flyway runs as this role.
--   tinfirm_app    the runtime role. It owns nothing, so row-level security applies to
--                  it. Migrations GRANT it exactly what it needs. This file grants it only
--                  CONNECT and schema USAGE, and deliberately sets no default privileges:
--                  a blanket grant would give it UPDATE/DELETE on the append-only audit
--                  tables.
--
-- The passwords come from the container environment through psql's \getenv, so their
-- only copy is deploy/compose/.env. To reuse this file elsewhere (e.g. a Testcontainers
-- PostgreSQL), mount it into /docker-entrypoint-initdb.d and set TINFIRM_OWNER_PASSWORD
-- and TINFIRM_APP_PASSWORD on that container.
--
-- LiteLLM's database `litellm` and its role `litellm` are not created here but in
-- litellm.sql, which the service `litellm-db` runs on every `up`: it is idempotent, so it
-- also reaches a volume that already exists.

\set ON_ERROR_STOP on

\getenv owner_password TINFIRM_OWNER_PASSWORD
\getenv app_password TINFIRM_APP_PASSWORD

\if :{?owner_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'TINFIRM_OWNER_PASSWORD is not set in the postgres container environment'; END $$;
\endif
\if :{?app_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'TINFIRM_APP_PASSWORD is not set in the postgres container environment'; END $$;
\endif

CREATE ROLE tinfirm_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  PASSWORD :'owner_password';
CREATE ROLE tinfirm_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  PASSWORD :'app_password';

CREATE DATABASE tinfirm OWNER tinfirm_owner;

-- Only the owner (implicitly) and the app role may connect.
REVOKE ALL ON DATABASE tinfirm FROM PUBLIC;
GRANT CONNECT ON DATABASE tinfirm TO tinfirm_app;

\connect tinfirm

-- Since PostgreSQL 15, schema public belongs to pg_database_owner (here: tinfirm_owner)
-- and PUBLIC cannot CREATE in it. Also take away PUBLIC's USAGE, and give it back to the
-- app role alone. A migration that creates its own schema grants USAGE on it itself.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tinfirm_app;
