-- Outputs and attachments of conversations (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md
-- §3.11, §3.13, §5.4 and open point 5): package 3c's third part. V005 created output and attachment; this file adds
-- what part 3 keeps beside them.
--
--   attachment.file_name   a document from a run's /output keeps its path relative to the folder as its name
--   run_change_request     the change requests the server opened, one per conversation and repository, keyed by
--                          their CHANGE_REQUEST output, with the state as last read from the code host
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company, and grants to the runtime role ${app_role}.

-- ---- attachment: the relative paths of /output ---------------------------------------------------------------------

-- A document from a run's /output keeps its path relative to the folder as its name (reports/q3.md): segments
-- separated by '/', none of them empty, '.' or '..' or longer than 255 characters, at most 500 characters in all. A
-- name without '/' is a single segment, as a person's file always is (the server). The runner host mounts the files
-- of later turns under names of one segment that the server derives from these.
ALTER TABLE attachment DROP CONSTRAINT attachment_name_ck;
ALTER TABLE attachment ALTER COLUMN file_name TYPE varchar(500);
ALTER TABLE attachment ADD CONSTRAINT attachment_name_ck CHECK (length(file_name) BETWEEN 1 AND 500
    AND position('\' IN file_name) = 0
    AND left(file_name, 1) <> '/' AND right(file_name, 1) <> '/' AND position('//' IN file_name) = 0
    AND file_name !~ '(^|/)\.\.?(/|$)'
    AND file_name !~ '[^/]{255}[^/]');

-- ---- output: the target of run_change_request's key ------------------------------------------------------------------

ALTER TABLE output ADD CONSTRAINT output_id_kind_uq UNIQUE (id, company_id, kind);

-- ---- run_change_request (contract §3.11) -----------------------------------------------------------------------------

-- The change requests the server opened after a turn pushed (contract §5.4, open point 5), one per conversation and
-- repository, keyed by the CHANGE_REQUEST output that shows them (output_kind is always CHANGE_REQUEST: the foreign
-- key with it admits no other output).
--   run_id             the turn whose push it was opened for
--   binding_id         the conversation's binding on the code host: it was opened, and its state is read, with
--                      that account only (ConversationBindings.use, purpose change-request:{runId})
--   number             the pull request's number, or the project-scoped merge request iid
--   state              as last read, or as the code host answered when it was opened; NULL before the first read
--   state_checked_at   when the state was last read; NULL: due now (a later push makes it due at once)
--   watched            whether the state is still read: until MERGED, the conversation ended, or its binding gone
-- A removed repository takes its row along (the output keeps its link, without the repository).
CREATE TABLE run_change_request (
    output_id        uuid          PRIMARY KEY,
    company_id       uuid          NOT NULL,
    output_kind      varchar(16)   NOT NULL DEFAULT 'CHANGE_REQUEST',
    conversation_id  uuid          NOT NULL,
    run_id           uuid          NOT NULL,
    repository_id    uuid          NOT NULL,
    binding_id       uuid          NOT NULL,
    number           integer       NOT NULL,
    url              varchar(2000) NOT NULL,
    state            varchar(16),
    state_checked_at timestamptz,
    watched          boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT run_change_request_output_kind_ck CHECK (output_kind = 'CHANGE_REQUEST'),
    CONSTRAINT run_change_request_output_fk FOREIGN KEY (output_id, company_id, output_kind)
        REFERENCES output (id, company_id, kind),
    CONSTRAINT run_change_request_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT run_change_request_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT run_change_request_repository_fk FOREIGN KEY (repository_id, company_id)
        REFERENCES system_repository (id, company_id) ON DELETE CASCADE,
    CONSTRAINT run_change_request_binding_fk FOREIGN KEY (binding_id, company_id)
        REFERENCES conversation_binding (id, company_id),
    -- One change request per conversation and repository: a later push goes to the same one.
    CONSTRAINT run_change_request_repository_uq UNIQUE (conversation_id, repository_id),
    CONSTRAINT run_change_request_number_ck CHECK (number > 0),
    CONSTRAINT run_change_request_url_ck CHECK (url ~ '^https?://[^/?#@[:space:]]+([/?#][^[:space:]]*)?$'),
    CONSTRAINT run_change_request_state_ck CHECK (state IS NULL OR state IN ('OPEN', 'DRAFT', 'MERGED', 'CLOSED'))
);

CREATE INDEX run_change_request_run_idx ON run_change_request (run_id);
CREATE INDEX run_change_request_repository_idx ON run_change_request (repository_id);
CREATE INDEX run_change_request_binding_idx ON run_change_request (binding_id);
-- The states to read: watched, never read or read before the interval.
CREATE INDEX run_change_request_due_idx ON run_change_request (state_checked_at) WHERE watched;

ALTER TABLE run_change_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_change_request FORCE ROW LEVEL SECURITY;

CREATE POLICY run_change_request_tenant ON run_change_request FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Changed in place (its state, whether it is watched), never deleted but with its repository.
GRANT SELECT, INSERT, UPDATE ON run_change_request TO ${app_role};

-- ---- cross-company lookup (CONVENTIONS §7, as in V004) ----------------------------------------------------------------

-- The watch of change requests (a worker replica) reads the due states of every company: ids and company ids only;
-- the server claims each row again in its company's tenant context. The owner gets a SELECT-only policy on the table
-- the function reads, which the runtime role never matches.
CREATE POLICY run_change_request_owner_lookup ON run_change_request FOR SELECT TO CURRENT_USER USING (true);

-- Watched change requests whose state was never read or was read before the cutoff, least recently read first.
CREATE FUNCTION run_change_request_state_due(p_checked_before timestamptz, p_limit integer)
    RETURNS TABLE (output_id uuid, company_id uuid, repository_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT c.output_id, c.company_id, c.repository_id
      FROM ${flyway:defaultSchema}.run_change_request c
     WHERE c.watched
       AND (c.state_checked_at IS NULL OR c.state_checked_at < p_checked_before)
     ORDER BY c.state_checked_at NULLS FIRST, c.output_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION run_change_request_state_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION run_change_request_state_due(timestamptz, integer) TO ${app_role};
