-- The runtime role loses DELETE on personal_runner_credential (the security review of 2026-10-27, §7; CONVENTIONS §7:
-- "The runtime role has SELECT, INSERT and UPDATE on personal_runner_credential (a credential goes with its machine
-- only)"; "migrations GRANT it only what it needs").
--
-- V064 granted all four rights, but no code deletes a credential: one a machine no longer reports is marked
-- reported = false, and the rows go with their machine (ON DELETE CASCADE from personal_runner_host, a referential
-- action PostgreSQL runs as the table's owner). The grant now matches what the conventions say.

REVOKE DELETE ON personal_runner_credential FROM ${app_role};
