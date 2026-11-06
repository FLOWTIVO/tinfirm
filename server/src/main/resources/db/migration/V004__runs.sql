-- Runs as the turns of conversations (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md §3.11,
-- §5.7; PLATFORM.md §11): package 3b of the platform redesign. A turn is one run: one execution of the conversation's
-- agent in a sandbox on a runner host, with its events, its durable commands and the repositories of its workspace.
-- The tables are the restart's run, run_event, run_command and run_repository with the conversation as the parent
-- instead of the job and its work item, the payer's model access (package 2) instead of the agent's runner or model
-- credential, and the host's kind.
--
-- run.agent_id points at the agent (V003), which is deleted only softly and so keeps its runs' history. The
-- conversation (package 3c) is built after this file: run.conversation_id carries no foreign key here. Package 3c adds
-- the key to conversation, run_repository.binding_id's to conversation_binding, and the run ids its own tables store.
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), a composite UNIQUE (id, company_id) that composite foreign keys point at, and grants to the
-- runtime role ${app_role}, which owns nothing. The schema owner runs this file.

-- ---- run ---------------------------------------------------------------------------------------------------------

--   conversation_id       the conversation whose turn the run is (workItem.id and job.id on the wire, contract §5.7)
--   conversation_key      a snapshot of the conversation's key (keys never change, I12): the branch agent/{key}
--   agent_id, runtime     the conversation's agent and its runtime, fixed at the conversation's start (open point 3)
--   turn_reason           FIRST, MESSAGE, DECIDED, CONNECTED, RETRY (packages 4 and 5 add EVENT, REVIEW_RETURNED);
--                         turn_number the turn's number in its conversation
--   acting_principal_id   the person the run acts for
--   payer_principal_id,   who pays, copied from the conversation (I10, M2): the principal's BUDGET (its LiteLLM key,
--   model_access,         through the model relay) or a SUBSCRIPTION on the payer's personal host, named by the host
--   subscription_host_id, and the label the host reports (package 2's ModelAccessChoice)
--   subscription_label
--   runner_host_id,       set when the run is assigned; the name and the kind are snapshots that keep the history
--   runner_host_name,     readable after the host is gone
--   runner_host_kind
--   status                QUEUED (dispatched_at set: assigned, awaiting run.accepted), RUNNING, SUCCEEDED, FAILED,
--                         CANCELLED, LOST
--   epoch                 fencing token, incremented on every assignment; frames with another epoch are stale, and
--                         the run's one-run model token answers only for its epoch (package 2, M7)
--   dispatch_after        not dispatched before this time (back-off after a rejection)
--   lease_expires_at      a RUNNING run's lease; for an assigned run the time the accept timeout waits for (the
--                         preparation of its run.assign, or the reconnect grace)
--   failure_class         the runner's classes, LOST (the server lost the run), and the server's RUNTIME_REMOVED and
--                         MODEL_NOT_ALLOWED (contract §5.7)
--   summary               the agent's finish summary, else its last message; result_* the structured finish result
--   queue_reason*         why a QUEUED run waits (the API's QueueReasonCode, its other fields as JSON, since when)
--   lost_cause            why a LOST run was lost; LEASE_EXPIRED is the only cause that can be resumed
--   relay_*               what the model relay passed for the run, recorded when it ends (package 2 §6.1)
CREATE TABLE run (
    id                    uuid          PRIMARY KEY,
    company_id            uuid          NOT NULL,
    conversation_id       uuid          NOT NULL,
    conversation_key      varchar(93)   NOT NULL,
    agent_id              uuid          NOT NULL,
    runtime               varchar(16)   NOT NULL,
    turn_reason           varchar(16)   NOT NULL,
    turn_number           integer       NOT NULL,
    acting_principal_id   uuid          NOT NULL,
    payer_principal_id    uuid          NOT NULL,
    model_access          varchar(16)   NOT NULL,
    subscription_host_id  uuid,
    subscription_label    varchar(63),
    runner_host_id        uuid,
    runner_host_name      varchar(100),
    runner_host_kind      varchar(8),
    status                varchar(16)   NOT NULL,
    epoch                 bigint        NOT NULL DEFAULT 0,
    dispatched_at         timestamptz,
    dispatch_after        timestamptz,
    lease_expires_at      timestamptz,
    created_by            uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    created_at            timestamptz   NOT NULL,
    started_at            timestamptz,
    finished_at           timestamptz,
    failure_class         varchar(32),
    failure_message       varchar(2000),
    summary               text,
    session_id            varchar(255),
    branch                varchar(255),
    input_tokens          bigint,
    output_tokens         bigint,
    cached_input_tokens   bigint,
    cache_write_tokens    bigint,
    cost_usd              double precision,
    last_event_seq        bigint        NOT NULL DEFAULT 0,
    event_count           integer       NOT NULL DEFAULT 0,
    queue_reason          varchar(32),
    queue_reason_details  jsonb,
    queue_reason_since    timestamptz,
    lost_cause            varchar(32),
    result_outcome        varchar(16),
    result_next_steps     jsonb,
    result_open_questions jsonb,
    tool_call_count       integer,
    message_count         integer,
    thought_count         integer,
    resumed               boolean,
    resume_outcome        varchar(16),
    relay_requests        bigint,
    relay_request_bytes   bigint,
    relay_response_bytes  bigint,
    CONSTRAINT run_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT run_agent_fk FOREIGN KEY (agent_id, company_id) REFERENCES agent (id, company_id),
    CONSTRAINT run_acting_principal_fk FOREIGN KEY (acting_principal_id, company_id)
        REFERENCES principal (id, company_id),
    CONSTRAINT run_payer_principal_fk FOREIGN KEY (payer_principal_id, company_id)
        REFERENCES principal (id, company_id),
    CONSTRAINT run_host_fk FOREIGN KEY (runner_host_id, company_id)
        REFERENCES runner_host (id, company_id) ON DELETE SET NULL (runner_host_id),
    CONSTRAINT run_subscription_host_fk FOREIGN KEY (subscription_host_id, company_id)
        REFERENCES runner_host (id, company_id) ON DELETE SET NULL (subscription_host_id),
    CONSTRAINT run_conversation_key_ck CHECK (length(conversation_key) BETWEEN 1 AND 93),
    CONSTRAINT run_runtime_ck CHECK (runtime IN ('CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE')),
    CONSTRAINT run_turn_reason_ck CHECK (turn_reason IN ('FIRST', 'MESSAGE', 'DECIDED', 'CONNECTED', 'RETRY')),
    CONSTRAINT run_turn_number_ck CHECK (turn_number >= 1),
    -- I10: a personal conversation's payer is its person; package 4 relaxes this for a trigger's runs.
    CONSTRAINT run_payer_ck CHECK (payer_principal_id = acting_principal_id),
    CONSTRAINT run_model_access_ck CHECK (
        (model_access = 'BUDGET' AND subscription_host_id IS NULL AND subscription_label IS NULL)
        OR (model_access = 'SUBSCRIPTION' AND subscription_label IS NOT NULL)),
    CONSTRAINT run_host_kind_ck CHECK (runner_host_kind IS NULL OR runner_host_kind IN ('COMPANY', 'PERSONAL')),
    CONSTRAINT run_status_ck CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'LOST')),
    CONSTRAINT run_failure_class_ck CHECK (failure_class IS NULL OR failure_class IN (
        'USAGE_LIMIT', 'SERVER_LIMITED', 'CONTEXT_OVERFLOW', 'LOGIN_EXPIRED', 'VENDOR_BLOCKED', 'CREDENTIAL_LEAK',
        'PROTOCOL_ERROR', 'GUARD_STOPPED', 'GIT_ERROR', 'INFRA', 'RUNNER_UNAVAILABLE', 'MODEL_REFUSED', 'LOST',
        'RUNTIME_REMOVED', 'MODEL_NOT_ALLOWED')),
    CONSTRAINT run_epoch_ck CHECK (epoch >= 0),
    CONSTRAINT run_dispatch_ck CHECK (dispatched_at IS NULL OR status = 'QUEUED'),
    CONSTRAINT run_lease_ck CHECK (status <> 'RUNNING' OR lease_expires_at IS NOT NULL),
    CONSTRAINT run_finished_ck CHECK ((status IN ('QUEUED', 'RUNNING')) = (finished_at IS NULL)),
    CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
        'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'HOST_OFFLINE', 'MODEL_ACCESS_UNAVAILABLE',
        'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING',
        'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED',
        'OTHER')),
    CONSTRAINT run_queue_reason_status_ck CHECK (queue_reason IS NULL OR status = 'QUEUED'),
    CONSTRAINT run_queue_reason_since_ck CHECK ((queue_reason IS NULL) = (queue_reason_since IS NULL)),
    CONSTRAINT run_queue_reason_details_ck CHECK (queue_reason IS NOT NULL OR queue_reason_details IS NULL),
    CONSTRAINT run_lost_cause_ck CHECK (lost_cause IS NULL OR (status = 'LOST' AND lost_cause IN (
        'LEASE_EXPIRED', 'HOST_RESTARTED', 'STALE_ON_RECONNECT'))),
    CONSTRAINT run_result_outcome_ck CHECK (result_outcome IS NULL OR result_outcome IN ('COMPLETED', 'BLOCKED')),
    CONSTRAINT run_result_ck CHECK (
        (result_outcome IS NULL AND result_next_steps IS NULL AND result_open_questions IS NULL)
        OR (result_outcome IS NOT NULL
            AND jsonb_typeof(result_next_steps) = 'array' AND jsonb_array_length(result_next_steps) <= 20
            AND jsonb_typeof(result_open_questions) = 'array' AND jsonb_array_length(result_open_questions) <= 10)),
    CONSTRAINT run_counts_ck CHECK (
        (tool_call_count IS NULL AND message_count IS NULL AND thought_count IS NULL)
        OR (tool_call_count >= 0 AND message_count >= 0 AND thought_count >= 0)),
    CONSTRAINT run_resume_outcome_ck CHECK (resume_outcome IS NULL OR resume_outcome IN (
        'FIRST_RUN', 'NO_SESSION', 'LOAD_FAILED', 'NOT_SUPPORTED')),
    CONSTRAINT run_resumed_without_outcome_ck CHECK (resumed IS NOT TRUE OR resume_outcome IS NULL),
    CONSTRAINT run_resume_outcome_resumed_ck CHECK (resume_outcome IS NULL OR resumed IS NOT NULL),
    CONSTRAINT run_relay_ck CHECK (
        (relay_requests IS NULL AND relay_request_bytes IS NULL AND relay_response_bytes IS NULL)
        OR (relay_requests >= 0 AND relay_request_bytes >= 0 AND relay_response_bytes >= 0))
);

-- I11: one run at a time per conversation; conversations run in parallel (contract §3.11).
CREATE UNIQUE INDEX run_one_active_per_conversation_uq ON run (conversation_id)
    WHERE status IN ('QUEUED', 'RUNNING');

-- The turns of a conversation, oldest first; its previous run (session, host).
CREATE INDEX run_conversation_idx ON run (conversation_id, created_at, id);
-- Dispatcher: queued runs, oldest first.
CREATE INDEX run_queue_idx ON run (created_at, id) WHERE status = 'QUEUED';
-- Reaper: running runs by lease.
CREATE INDEX run_lease_idx ON run (lease_expires_at) WHERE status = 'RUNNING';
-- Slot accounting and "has active runs" checks.
CREATE INDEX run_active_host_idx ON run (runner_host_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX run_active_subscription_idx ON run (subscription_host_id, subscription_label)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX run_active_agent_idx ON run (agent_id) WHERE status IN ('QUEUED', 'RUNNING');
-- The foreign keys' lookups.
CREATE INDEX run_host_idx ON run (runner_host_id) WHERE runner_host_id IS NOT NULL;
CREATE INDEX run_subscription_host_idx ON run (subscription_host_id) WHERE subscription_host_id IS NOT NULL;
CREATE INDEX run_acting_principal_idx ON run (acting_principal_id);
CREATE INDEX run_payer_principal_idx ON run (payer_principal_id);
CREATE INDEX run_created_by_idx ON run (created_by) WHERE created_by IS NOT NULL;

-- ---- run_event ---------------------------------------------------------------------------------------------------

-- Payloads are stored unchanged (protocol/runner-v2.schema.json $defs/EventPayload*). At most 20 000 events per run;
-- LOG events are dropped first.
CREATE TABLE run_event (
    run_id     uuid        NOT NULL,
    seq        bigint      NOT NULL,
    company_id uuid        NOT NULL,
    type       varchar(32) NOT NULL,
    at         timestamptz NOT NULL,
    payload    jsonb       NOT NULL,
    CONSTRAINT run_event_pk PRIMARY KEY (run_id, seq),
    CONSTRAINT run_event_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT run_event_seq_ck CHECK (seq >= 1),
    CONSTRAINT run_event_type_ck CHECK (type IN ('AGENT_MESSAGE', 'AGENT_THOUGHT', 'TOOL_CALL', 'TOOL_CALL_UPDATE',
        'PLAN', 'USAGE', 'RATE_LIMIT', 'STATUS', 'GIT', 'LOG', 'ERROR')),
    CONSTRAINT run_event_payload_ck CHECK (jsonb_typeof(payload) = 'object')
);

-- The oldest LOG event of a run, dropped when the run reaches the cap.
CREATE INDEX run_event_log_idx ON run_event (run_id, seq) WHERE type = 'LOG';

-- ---- run_command -------------------------------------------------------------------------------------------------

-- Durable commands to runner hosts (one kind, CANCEL). Pending commands are sent again when the host reconnects,
-- and completed when the run ends.
CREATE TABLE run_command (
    id           uuid        PRIMARY KEY,
    company_id   uuid        NOT NULL,
    run_id       uuid        NOT NULL,
    type         varchar(16) NOT NULL,
    status       varchar(16) NOT NULL,
    created_by   uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL,
    last_sent_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT run_command_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT run_command_type_ck CHECK (type IN ('CANCEL')),
    CONSTRAINT run_command_status_ck CHECK (status IN ('PENDING', 'DONE'))
);

CREATE UNIQUE INDEX run_command_pending_uq ON run_command (run_id, type) WHERE status = 'PENDING';
CREATE INDEX run_command_created_by_idx ON run_command (created_by) WHERE created_by IS NOT NULL;

-- ---- run_repository ----------------------------------------------------------------------------------------------

-- The workspace a run was assigned with (contract §5.6), one row per repository in workspace order, written with the
-- assignment from what the turn's source gave (an assignment of a later epoch replaces them); run.finished fills the
-- git columns, matched by repository.
--   repository_id,   the repository and its code host; NULL after they are removed. name: the repository's name when
--   system_id        the run was assigned
--   binding_id       the conversation's binding whose account cloned it (package 3c adds the foreign key)
--   dir              the directory under /workspace, unique within the run
--   read_only        cloned, never pushed, no change request; read_only_cause why: the agent's access (AGENT), the
--                    binding's read-only grant (GRANT), or an approval that gave READ (REQUEST)
CREATE TABLE run_repository (
    run_id          uuid         NOT NULL,
    company_id      uuid         NOT NULL,
    position        smallint     NOT NULL,
    repository_id   uuid,
    system_id       uuid,
    binding_id      uuid,
    name            varchar(200) NOT NULL,
    dir             varchar(64)  NOT NULL,
    read_only       boolean      NOT NULL DEFAULT false,
    read_only_cause varchar(16),
    pushed          boolean,
    head_sha        varchar(64),
    commits         integer,
    CONSTRAINT run_repository_pk PRIMARY KEY (run_id, position),
    CONSTRAINT run_repository_dir_uq UNIQUE (run_id, dir),
    CONSTRAINT run_repository_repository_uq UNIQUE (run_id, repository_id),
    CONSTRAINT run_repository_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT run_repository_repository_fk FOREIGN KEY (repository_id, company_id)
        REFERENCES system_repository (id, company_id) ON DELETE SET NULL (repository_id),
    CONSTRAINT run_repository_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE SET NULL (system_id),
    CONSTRAINT run_repository_position_ck CHECK (position BETWEEN 0 AND 19),
    CONSTRAINT run_repository_dir_ck CHECK (dir ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(dir) <= 64),
    CONSTRAINT run_repository_read_only_cause_ck CHECK (
        (NOT read_only AND read_only_cause IS NULL)
        OR (read_only AND read_only_cause IS NOT NULL AND read_only_cause IN ('AGENT', 'GRANT', 'REQUEST'))),
    CONSTRAINT run_repository_commits_ck CHECK (commits IS NULL OR commits >= 0)
);

-- The ON DELETE SET NULL of the repository and system foreign keys; the bindings' rows.
CREATE INDEX run_repository_repository_idx ON run_repository (repository_id) WHERE repository_id IS NOT NULL;
CREATE INDEX run_repository_system_idx ON run_repository (system_id) WHERE system_id IS NOT NULL;
CREATE INDEX run_repository_binding_idx ON run_repository (binding_id) WHERE binding_id IS NOT NULL;

-- ---- row-level security and grants ---------------------------------------------------------------------------

ALTER TABLE run ENABLE ROW LEVEL SECURITY;
ALTER TABLE run FORCE ROW LEVEL SECURITY;
ALTER TABLE run_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_event FORCE ROW LEVEL SECURITY;
ALTER TABLE run_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_command FORCE ROW LEVEL SECURITY;
ALTER TABLE run_repository ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_repository FORCE ROW LEVEL SECURITY;

CREATE POLICY run_tenant ON run FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY run_event_tenant ON run_event FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY run_command_tenant ON run_command FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY run_repository_tenant ON run_repository FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Runs and their commands are never deleted. A run's repositories are replaced by each assignment until one is
-- accepted; events are deleted only to enforce the per-run cap.
GRANT SELECT, INSERT, UPDATE ON run, run_command TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON run_repository TO ${app_role};
GRANT SELECT, INSERT, DELETE ON run_event TO ${app_role};

-- ---- cross-company lookups (CONVENTIONS §7, as in V001 and V002) -------------------------------------------------

-- Narrow SECURITY DEFINER lookups for trusted server work: the dispatcher (queued runs of every company, oldest
-- first), the reaper (overdue accepts, expired leases, lost runs whose model tokens are still unrevoked) and the
-- reconnect grace at startup (the companies with runs that hold a slot). Each returns ids and company ids only; the
-- server then re-checks every row under its lock in the company's tenant context. Because the tables use FORCE ROW
-- LEVEL SECURITY, the owner gets a SELECT-only policy on the tables the functions read; the runtime role never
-- matches it (run_model_token's is V002's).

CREATE POLICY run_owner_lookup ON run FOR SELECT TO CURRENT_USER USING (true);

-- Queued runs that are not assigned and not backing off, oldest first, after a (created_at, id) position so the
-- dispatcher can walk the whole queue: runs that wait for an offline host must not starve younger ones.
CREATE FUNCTION run_dispatch_queue(p_now timestamptz, p_after_created_at timestamptz, p_after_id uuid,
                                   p_limit integer)
    RETURNS TABLE (run_id uuid, company_id uuid, created_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, r.company_id, r.created_at
      FROM ${flyway:defaultSchema}.run r
     WHERE r.status = 'QUEUED'
       AND r.dispatched_at IS NULL
       AND (r.dispatch_after IS NULL OR r.dispatch_after <= p_now)
       AND (p_after_created_at IS NULL OR (r.created_at, r.id) > (p_after_created_at, p_after_id))
     ORDER BY r.created_at, r.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- Assigned runs whose run.accepted did not arrive in time, and whose grace (lease_expires_at) is over.
CREATE FUNCTION run_accept_overdue(p_dispatched_before timestamptz, p_now timestamptz, p_limit integer)
    RETURNS TABLE (run_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, r.company_id
      FROM ${flyway:defaultSchema}.run r
     WHERE r.status = 'QUEUED'
       AND r.dispatched_at < p_dispatched_before
       AND (r.lease_expires_at IS NULL OR r.lease_expires_at < p_now)
     ORDER BY r.dispatched_at, r.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- Running runs whose lease has expired.
CREATE FUNCTION run_lease_expired(p_now timestamptz, p_limit integer)
    RETURNS TABLE (run_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, r.company_id
      FROM ${flyway:defaultSchema}.run r
     WHERE r.status = 'RUNNING'
       AND r.lease_expires_at < p_now
     ORDER BY r.lease_expires_at, r.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- Runs lost by lease expiry before the cutoff (the end of their resume window) whose one-run model tokens are not
-- revoked yet: a run lost that way keeps its token while its host may still bring it back, and the reaper revokes it
-- once that can no longer happen.
CREATE FUNCTION run_lost_unrevoked(p_finished_before timestamptz, p_limit integer)
    RETURNS TABLE (run_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, r.company_id
      FROM ${flyway:defaultSchema}.run r
     WHERE r.status = 'LOST'
       AND r.lost_cause = 'LEASE_EXPIRED'
       AND r.finished_at < p_finished_before
       AND EXISTS (SELECT 1
                     FROM ${flyway:defaultSchema}.run_model_token t
                    WHERE t.run_id = r.id AND t.company_id = r.company_id AND t.revoked_at IS NULL)
     ORDER BY r.finished_at, r.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- The companies that have runs holding a slot (running, or assigned and awaiting run.accepted): the server extends
-- their leases when it starts.
CREATE FUNCTION run_occupying_companies()
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT DISTINCT r.company_id
      FROM ${flyway:defaultSchema}.run r
     WHERE r.status = 'RUNNING' OR (r.status = 'QUEUED' AND r.dispatched_at IS NOT NULL)
$$;

REVOKE ALL ON FUNCTION run_dispatch_queue(timestamptz, timestamptz, uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION run_accept_overdue(timestamptz, timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION run_lease_expired(timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION run_lost_unrevoked(timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION run_occupying_companies() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION run_dispatch_queue(timestamptz, timestamptz, uuid, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION run_accept_overdue(timestamptz, timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION run_lease_expired(timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION run_lost_unrevoked(timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION run_occupying_companies() TO ${app_role};
