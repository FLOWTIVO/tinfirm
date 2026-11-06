-- TEST FIXTURE ONLY. Mirrors what deploy/compose/postgres/init.sql must do:
-- the schema owner runs Flyway, the runtime role only gets what the migrations grant.
CREATE ROLE tinfirm_owner LOGIN PASSWORD 'owner-test-password';
CREATE ROLE tinfirm_app LOGIN PASSWORD 'app-test-password';
-- In PostgreSQL 15+ the public schema belongs to pg_database_owner, i.e. to tinfirm_owner after this.
ALTER DATABASE tinfirm OWNER TO tinfirm_owner;
