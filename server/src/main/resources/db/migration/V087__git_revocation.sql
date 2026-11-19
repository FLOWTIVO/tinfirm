-- ADR-0116, decisions 1 and 2: the server tells a run's host to drop a repository's git token when the conversation's
-- binding fails its check, its system is paused, or a trigger's binding follows another credential.
--
--   run_command.type          gains REVOKE_GIT beside CANCEL. A REVOKE_GIT row names the repositories whose token
--                             goes (component_ids, one to 20: run_repository.component_id), the reason (a binding's
--                             reason, or SYSTEM_PAUSED, CREDENTIAL_REPLACED, REFRESH_REFUSED) and the message for the
--                             run's log and the agent (at most 1000 characters), for the run's assignment of epoch.
--                             Like CANCEL it stays PENDING until the run ends and is sent again at every hello that
--                             lists the run, but only for its epoch and to a host that lists run.git.revoke;
--                             last_sent_at is set once the frame went. A run may have several: one per cause, each
--                             naming the repositories no earlier one of its epoch named, so the one pending row per
--                             run and type holds for CANCEL alone.
--   run_repository.component_id  the repository's id as run.assign named it (workspaces[].componentId): the same as
--                             repository_id, but kept after the repository is removed, which nulls repository_id;
--                             NULL for rows from before V087 whose repository is gone
--   run_repository.epoch      the assignment that wrote the row; a later assignment writes its own. NULL for rows from
--                             before V087: a revocation names only the repositories of the run's current assignment
--
-- Both tables keep their forced row-level security and their grants; no table is added.

ALTER TABLE run_command
    ADD COLUMN epoch         bigint,
    ADD COLUMN component_ids uuid[],
    ADD COLUMN reason        varchar(64),
    ADD COLUMN message       varchar(1000);

ALTER TABLE run_command DROP CONSTRAINT run_command_type_ck;
ALTER TABLE run_command ADD CONSTRAINT run_command_type_ck CHECK (type IN ('CANCEL', 'REVOKE_GIT'));
ALTER TABLE run_command ADD CONSTRAINT run_command_revoke_git_ck CHECK (
    (type = 'CANCEL' AND epoch IS NULL AND component_ids IS NULL AND reason IS NULL AND message IS NULL)
    OR (type = 'REVOKE_GIT' AND epoch IS NOT NULL AND component_ids IS NOT NULL
        AND cardinality(component_ids) BETWEEN 1 AND 20 AND array_position(component_ids, NULL) IS NULL
        AND reason IS NOT NULL AND reason ~ '^[A-Z][A-Z0-9_]{0,63}$'
        AND message IS NOT NULL AND length(message) BETWEEN 1 AND 1000));

DROP INDEX run_command_pending_uq;
CREATE UNIQUE INDEX run_command_pending_uq ON run_command (run_id, type) WHERE status = 'PENDING' AND type = 'CANCEL';
CREATE INDEX run_command_revoke_git_idx ON run_command (run_id, epoch) WHERE type = 'REVOKE_GIT';

ALTER TABLE run_repository
    ADD COLUMN component_id uuid,
    ADD COLUMN epoch        bigint;

-- The schema owner bypasses row-level security for this step only, as in V033 and V080, on the tables it changes
-- and reads. Nothing checks a changed row of these tables against another table. The running and lost runs at the
-- upgrade get their current epoch, so that a revocation reaches them too; an assigned run's rows may be an earlier
-- assignment's, and get none.
ALTER TABLE run_repository NO FORCE ROW LEVEL SECURITY;
ALTER TABLE run NO FORCE ROW LEVEL SECURITY;

UPDATE run_repository SET component_id = repository_id WHERE repository_id IS NOT NULL;

UPDATE run_repository rr
   SET epoch = r.epoch
  FROM run r
 WHERE r.id = rr.run_id AND r.company_id = rr.company_id AND r.status IN ('RUNNING', 'LOST');

ALTER TABLE run FORCE ROW LEVEL SECURITY;
ALTER TABLE run_repository FORCE ROW LEVEL SECURITY;
