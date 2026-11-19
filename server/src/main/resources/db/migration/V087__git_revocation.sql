-- ADR-0116, decisions 1 and 2: the server tells a run's host to drop a repository's git token when the conversation's
-- binding fails its check, its system is paused, or a trigger's binding follows another credential.
--
--   run_command.type          gains REVOKE_GIT beside CANCEL. A REVOKE_GIT row names the repositories whose token
--                             goes (component_ids, one to 20: run_repository.component_id), the reason (a binding's
--                             reason, or SYSTEM_PAUSED, CREDENTIAL_REPLACED, REFRESH_REFUSED, BINDING_CHANGED) and the
--                             message for the run's log and the agent (at most 1000 characters), for the run's
--                             assignment of epoch. Like CANCEL it stays PENDING until the run ends and is sent again at
--                             every hello that lists the run, but only for its epoch and to a host that lists
--                             run.git.revoke. A run may have several: one per cause, each naming the repositories no
--                             earlier one of its epoch named, so the one pending row per run and type holds for CANCEL
--                             alone.
--   run_command.delivery      a REVOKE_GIT row's delivery: SENT once its frame was written to the host's connection
--                             (last_sent_at says when), ON_RECONNECT while it was not (the host was not connected, or a
--                             send failed), UNSUPPORTED while the run's host does not list run.git.revoke. NULL for CANCEL
--   run_repository.component_id  the repository's id as run.assign named it (workspaces[].componentId): the same as
--                             repository_id, but kept after the repository is removed, which nulls repository_id;
--                             NULL for rows from before V087 whose repository is gone
--   run_repository.epoch      the assignment that wrote the row; a later assignment writes its own. NULL for rows from
--                             before V087 that no current assignment wrote: a revocation names only the repositories
--                             of the run's current assignment
--   run_repository.binding_version  conversation_binding.version when the assignment's token came from the binding;
--                             NULL for a public repository and for rows from before V087
--   conversation_binding.version  counts the changes of the binding's state, kind, account, holder and grant: a
--                             trigger (conversation_binding_version_trg) adds one at each such update, whatever writes
--                             it, conversation_rebind included. The assignment's third phase and a host's hello compare
--                             it with the version the token was revealed at
--
-- The tables keep their forced row-level security and their grants; no table is added.

ALTER TABLE run_command
    ADD COLUMN epoch         bigint,
    ADD COLUMN component_ids uuid[],
    ADD COLUMN reason        varchar(64),
    ADD COLUMN message       varchar(1000),
    ADD COLUMN delivery      varchar(16);

ALTER TABLE run_command DROP CONSTRAINT run_command_type_ck;
ALTER TABLE run_command ADD CONSTRAINT run_command_type_ck CHECK (type IN ('CANCEL', 'REVOKE_GIT'));
ALTER TABLE run_command ADD CONSTRAINT run_command_revoke_git_ck CHECK (
    (type = 'CANCEL' AND epoch IS NULL AND component_ids IS NULL AND reason IS NULL AND message IS NULL
        AND delivery IS NULL)
    OR (type = 'REVOKE_GIT' AND epoch IS NOT NULL AND component_ids IS NOT NULL
        AND cardinality(component_ids) BETWEEN 1 AND 20 AND array_position(component_ids, NULL) IS NULL
        AND reason IS NOT NULL AND reason ~ '^[A-Z][A-Z0-9_]{0,63}$'
        AND message IS NOT NULL AND length(message) BETWEEN 1 AND 1000
        AND delivery IN ('SENT', 'ON_RECONNECT', 'UNSUPPORTED')));

DROP INDEX run_command_pending_uq;
CREATE UNIQUE INDEX run_command_pending_uq ON run_command (run_id, type) WHERE status = 'PENDING' AND type = 'CANCEL';
CREATE INDEX run_command_revoke_git_idx ON run_command (run_id, epoch) WHERE type = 'REVOKE_GIT';

ALTER TABLE run_repository
    ADD COLUMN component_id    uuid,
    ADD COLUMN epoch           bigint,
    ADD COLUMN binding_version bigint;

ALTER TABLE conversation_binding ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- Not SECURITY DEFINER: it runs as whoever updates the row, and names no table.
CREATE FUNCTION conversation_binding_version() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM OLD.state OR NEW.how IS DISTINCT FROM OLD.how
       OR NEW.account_id IS DISTINCT FROM OLD.account_id
       OR NEW.holder_principal_id IS DISTINCT FROM OLD.holder_principal_id
       OR NEW.grant_id IS DISTINCT FROM OLD.grant_id THEN
        NEW.version := OLD.version + 1;
    ELSE
        NEW.version := OLD.version;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER conversation_binding_version_trg BEFORE UPDATE ON conversation_binding
    FOR EACH ROW EXECUTE FUNCTION conversation_binding_version();

-- The schema owner bypasses row-level security for this step only, as in V033 and V080, on the tables it changes
-- and reads. Nothing checks a changed row of these tables against another table. The runs whose current assignment
-- may have reached a host get its epoch on their rows, so that a revocation reaches them too: the running and lost
-- runs, and the assigned ones (QUEUED with dispatched_at). An assignment's third phase writes its rows and marks it
-- sent in one transaction, after deleting an earlier assignment's rows, so an assigned run's rows are its current
-- assignment's once that assignment was sent. One whose third phase had not committed when the server stopped was
-- never sent, and never is (no delivery survives a restart): its rows are an earlier assignment's or none, and a
-- revocation of them names an epoch its host never ran, which the host ignores, until the accept timeout takes the
-- run back to the queue.
ALTER TABLE run_repository NO FORCE ROW LEVEL SECURITY;
ALTER TABLE run NO FORCE ROW LEVEL SECURITY;

UPDATE run_repository SET component_id = repository_id WHERE repository_id IS NOT NULL;

UPDATE run_repository rr
   SET epoch = r.epoch
  FROM run r
 WHERE r.id = rr.run_id AND r.company_id = rr.company_id
   AND (r.status IN ('RUNNING', 'LOST') OR (r.status = 'QUEUED' AND r.dispatched_at IS NOT NULL));

ALTER TABLE run FORCE ROW LEVEL SECURITY;
ALTER TABLE run_repository FORCE ROW LEVEL SECURITY;
