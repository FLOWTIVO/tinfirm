-- Replay safety of tool calls and idempotent submissions (package R, docs/reviews/2026-10-12-package-r-contract.md
-- §3.2, §3.5; CONVENTIONS §15.14).
--
--   tool_call       the journal of a run's TOOL requests, one row per request that reached its call: claimed before
--                   the call runs, so that a request sent again (after a reconnect, to another replica, after a
--                   restart) gets the stored result and never a second call; a call a stopped replica left running is
--                   run again only when its tool is SAFE, else settled INTERRUPTED (the agent gets TOOL_INTERRUPTED)
--   submission_key  a caller's Idempotency-Key (REST) or requestId (MCP) of a submission, with what the first request
--                   made: the same key again answers that resource and makes, audits and charges nothing new
--
-- The conventions of V001 apply: company_id on every row, ENABLE plus FORCE ROW LEVEL SECURITY with a policy on
-- app_company_id(), composite foreign keys with the company id, grants to the runtime role ${app_role}, and SECURITY
-- DEFINER lookups that return ids only for the worker's sweeps across companies (as V015).

-- ---- tool_call -------------------------------------------------------------------------------------------------

--   run_id, request_id      the key: one row per TOOL request of a run (run.request's requestId)
--   conversation_id         the run's conversation
--   system_id, tool, name   what is called, as checked: the system, the tool as the system names it, and as the run
--                           lists it. No foreign key on the system: a removed system's rows stay with the run's history
--   effect, replay          the tool's effect (UNKNOWN counted as WRITE) and its replay class when it was checked
--   arguments_sha256        the SHA-256 of the canonical arguments (keys sorted, no whitespace): a request sent again
--                           with other arguments is INVALID_REQUEST, never a call
--   state                   RUNNING (claimed, its call under way), DONE (answered; the answer is result), INTERRUPTED
--                           (lost track of: an UNSAFE call is answered TOOL_INTERRUPTED; a SAFE one the sweep set so is
--                           claimed again when its request comes back)
--   attempt                 1, and one more for each claim again of a SAFE call
--   started_at, finished_at the claim of the current attempt, and its end
--   result                  the answer as it went out (run.request.ack's status with its payload, or code and message),
--                           within SystemTools' limits; cleared when the run ends
--   run_ended_at            when the run ended (TurnEnded); the row is deleted TINFIRM_TOOL_JOURNAL_RETENTION later. A
--                           row claimed while its run ended (the end did not reach it) is deleted that long after its
--                           claim
CREATE TABLE tool_call (
    company_id       uuid         NOT NULL,
    run_id           uuid         NOT NULL,
    request_id       uuid         NOT NULL,
    conversation_id  uuid         NOT NULL,
    system_id        uuid         NOT NULL,
    tool             varchar(64)  NOT NULL,
    name             varchar(100) NOT NULL,
    effect           varchar(8)   NOT NULL,
    replay           varchar(8)   NOT NULL,
    arguments_sha256 char(64)     NOT NULL,
    state            varchar(12)  NOT NULL,
    attempt          integer      NOT NULL DEFAULT 1,
    started_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    result           jsonb,
    run_ended_at     timestamptz,
    CONSTRAINT tool_call_pk PRIMARY KEY (run_id, request_id),
    CONSTRAINT tool_call_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT tool_call_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT tool_call_tool_ck CHECK (tool ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT tool_call_name_ck CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,99}$'),
    CONSTRAINT tool_call_effect_ck CHECK (effect IN ('READ', 'WRITE', 'UNKNOWN')),
    CONSTRAINT tool_call_replay_ck CHECK (replay IN ('SAFE', 'UNSAFE')),
    CONSTRAINT tool_call_sha_ck CHECK (arguments_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_call_state_ck CHECK (state IN ('RUNNING', 'DONE', 'INTERRUPTED')),
    CONSTRAINT tool_call_attempt_ck CHECK (attempt >= 1),
    -- A call ends exactly once it is no longer RUNNING, and only an ended call has an answer.
    CONSTRAINT tool_call_finished_ck CHECK ((state = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT tool_call_result_ck CHECK (result IS NULL OR state <> 'RUNNING'),
    CONSTRAINT tool_call_result_object_ck CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);

-- The calls under way, oldest claim first: the sweep's and a request's view of a stopped replica.
CREATE INDEX tool_call_running_idx ON tool_call (started_at) WHERE state = 'RUNNING';
-- The rows of ended runs, for their deletion after the journal's retention.
CREATE INDEX tool_call_run_ended_idx ON tool_call (run_ended_at) WHERE run_ended_at IS NOT NULL;
-- The rows of runs whose end has not reached them, by claim: the running runs' few, and a claim that raced its run's end.
CREATE INDEX tool_call_unended_idx ON tool_call (started_at) WHERE run_ended_at IS NULL;
-- A conversation's rows, for its purge and erasure.
CREATE INDEX tool_call_conversation_idx ON tool_call (conversation_id);

-- ---- submission_key --------------------------------------------------------------------------------------------

--   principal_id    the caller's principal in the company: a key is the caller's own, the same key from another
--                   member is another key
--   key             the client's Idempotency-Key or requestId: 1 to 200 visible ASCII characters
--   operation       the API's operation (its operationId): the same key with another is IDEMPOTENCY_KEY_REUSED
--   request_sha256  the SHA-256 of the operation's method, path and normalized body (an upload: of the file's hash,
--                   its name and its note): the same key with another request is IDEMPOTENCY_KEY_REUSED
--   resource_type,  what the first request made, which a replay answers as it is now. Written in the transaction that
--   resource_id     makes it, which inserts the key first: a concurrent request with the same key waits on the
--                   primary key, then finds the first one's resource; a request that failed leaves no key
--   created_at      the key is kept TINFIRM_IDEMPOTENCY_KEY_TTL (PT24H), then deleted by the worker's sweep
CREATE TABLE submission_key (
    company_id     uuid         NOT NULL,
    principal_id   uuid         NOT NULL,
    key            varchar(200) NOT NULL,
    operation      varchar(40)  NOT NULL,
    request_sha256 char(64)     NOT NULL,
    resource_type  varchar(16),
    resource_id    uuid,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT submission_key_pk PRIMARY KEY (company_id, principal_id, key),
    CONSTRAINT submission_key_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT submission_key_key_ck CHECK (key ~ '^[!-~]{1,200}$'),
    CONSTRAINT submission_key_operation_ck CHECK (operation IN ('startConversation', 'postConversationMessage',
        'uploadAttachment', 'retryConversation', 'decideDecision')),
    CONSTRAINT submission_key_sha_ck CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT submission_key_resource_type_ck
        CHECK (resource_type IN ('conversation', 'message', 'attachment', 'decision')),
    -- The resource is written with the key's own transaction: both or neither.
    CONSTRAINT submission_key_resource_ck CHECK ((resource_type IS NULL) = (resource_id IS NULL))
);

-- The keys past their lifetime, for the sweep.
CREATE INDEX submission_key_created_idx ON submission_key (created_at);

-- ---- row-level security and grants -----------------------------------------------------------------------------

ALTER TABLE tool_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_call FORCE ROW LEVEL SECURITY;
ALTER TABLE submission_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE submission_key FORCE ROW LEVEL SECURITY;

CREATE POLICY tool_call_tenant ON tool_call FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY submission_key_tenant ON submission_key FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A call's row changes with its attempts and is deleted after its run's retention or with its conversation's purge;
-- a key is written once with its resource and deleted after its lifetime.
GRANT SELECT, INSERT, UPDATE, DELETE ON tool_call, submission_key TO ${app_role};

-- ---- cross-company lookups (CONVENTIONS §7, as in V004, V008 and V015) -----------------------------------------

-- The worker's sweeps look at every company: ids and company ids only; the server settles or deletes each again in
-- its company's tenant context. The owner gets a SELECT-only policy on the tables the functions read, which the
-- runtime role never matches.
CREATE POLICY tool_call_owner_lookup ON tool_call FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY submission_key_owner_lookup ON submission_key FOR SELECT TO CURRENT_USER USING (true);

-- What the journal's sweep does, at most p_limit of each kind:
--   RUNNING  a call claimed before p_running_before (the tool timeout and 15 seconds ago) and still RUNNING: its
--            replica stopped, and its host never sent the request again; oldest claim first
--   EXPIRED  a company with rows of runs that ended before p_ended_before, or rows claimed before it whose run's end
--            never reached them (run_id and request_id null)
CREATE FUNCTION tool_call_due(p_running_before timestamptz, p_ended_before timestamptz, p_limit integer)
    RETURNS TABLE (kind text, company_id uuid, run_id uuid, request_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    (SELECT 'RUNNING'::text, t.company_id, t.run_id, t.request_id
       FROM ${flyway:defaultSchema}.tool_call t
      WHERE t.state = 'RUNNING' AND t.started_at < p_running_before
      ORDER BY t.started_at, t.run_id, t.request_id
      LIMIT least(greatest(p_limit, 1), 500))
    UNION ALL
    (SELECT 'EXPIRED'::text, e.company_id, NULL::uuid, NULL::uuid
       FROM (SELECT DISTINCT t.company_id
               FROM ${flyway:defaultSchema}.tool_call t
              WHERE t.run_ended_at < p_ended_before
                 OR (t.run_ended_at IS NULL AND t.started_at < p_ended_before)) e
      ORDER BY e.company_id
      LIMIT least(greatest(p_limit, 1), 500))
$$;

REVOKE ALL ON FUNCTION tool_call_due(timestamptz, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tool_call_due(timestamptz, timestamptz, integer) TO ${app_role};

-- The companies with keys created before p_created_before, for the sweep of keys past their lifetime.
CREATE FUNCTION submission_key_expired(p_created_before timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT e.company_id
      FROM (SELECT DISTINCT k.company_id
              FROM ${flyway:defaultSchema}.submission_key k
             WHERE k.created_at < p_created_before) e
     ORDER BY e.company_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION submission_key_expired(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION submission_key_expired(timestamptz, integer) TO ${app_role};
