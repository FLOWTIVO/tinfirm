-- LiteLLM's database and role on the Compose stack's PostgreSQL (ADR-0059). DEVELOPMENT and
-- single-host installs.
--
-- The service `litellm-db` runs this file on every `up`, as the superuser, before LiteLLM
-- starts. Unlike init.sql it is idempotent, so it also reaches a data volume that already
-- exists, and a new LITELLM_DB_PASSWORD in .env takes effect on the next `up`.
--
--   litellm   LiteLLM's own role. It owns the database `litellm` and nothing else, and
--             cannot connect to `tinfirm` (init.sql revokes CONNECT from PUBLIC there).
--             LiteLLM creates and migrates its tables itself (Prisma) at startup.
--
-- The Tinfirm roles get no access to this database, and this role none to Tinfirm's: the
-- server reaches LiteLLM through its admin API only. The password comes from the container
-- environment through psql's \getenv, so its only copy is deploy/compose/.env.

\set ON_ERROR_STOP on

\getenv litellm_password LITELLM_DB_PASSWORD

\if :{?litellm_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'LITELLM_DB_PASSWORD is not set in the litellm-db container environment'; END $$;
\endif

SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'litellm') AS create_role \gset
\if :create_role
  CREATE ROLE litellm WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD :'litellm_password';
\else
  ALTER ROLE litellm WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD :'litellm_password';
\endif

SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'litellm') AS create_database \gset
\if :create_database
  CREATE DATABASE litellm OWNER litellm ENCODING 'UTF8' TEMPLATE template0;
\endif

-- Only the owner may connect. Since PostgreSQL 15, schema public of the new database belongs
-- to pg_database_owner (here: litellm) and PUBLIC cannot CREATE in it.
REVOKE ALL ON DATABASE litellm FROM PUBLIC;
