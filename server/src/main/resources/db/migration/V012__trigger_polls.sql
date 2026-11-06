-- Polling, firings and Tinfirm's own writes (the package 4 contract, docs/reviews/2026-10-12-package-4-contract.md
-- §3.9 to §3.12, §5.4 to §5.6 and §5.10):
--
--   trigger_poll     one read of a system per account: triggers on the same account share it (§3.9)
--   trigger_subject  the poller's snapshot of a subject as one poll saw it, the edges' baseline (§3.10)
--   trigger_firing   each time a trigger fired, or was kept from firing for a reason worth showing (§3.11)
--   system_write     Tinfirm's record of what it wrote to a system, so that its own writes raise no event (§3.12)
--
-- and the SECURITY DEFINER lookups trigger_poll_due and trigger_schedule_due for the worker replicas, as V004's.
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company id, and grants to the runtime role ${app_role}.

-- ---- trigger_poll (contract §3.9) ----------------------------------------------------------------------------------

--   system_id, account_id,  the account it reads with (T8); one poll per system and account; it goes with its account
--   holder_principal_id     and its system
--   since                   the window's lower bound (PollWindow)
--   offset_keys             the subjects at the bound already handled (the window's offset)
--   cursor                  Jira Cloud's page token
--   etags                   GitHub's ETag per listing, sent as If-None-Match
--   status                  ACTIVE; IDLE (no active trigger); WAITING (the account needs a reconnect, or its holder is
--                           suspended)
--   next_poll_at, last_polled_at, lease_until
--   failures, backoff_until, failing_since, last_error   the backoff (§5.4)
--   rate_limit, rate_remaining, rate_resets_at           as the system last said
CREATE TABLE trigger_poll (
    id                  uuid          PRIMARY KEY,
    company_id          uuid          NOT NULL,
    system_id           uuid          NOT NULL,
    account_id          uuid          NOT NULL,
    holder_principal_id uuid          NOT NULL,
    since               timestamptz,
    offset_keys         varchar(255)[] NOT NULL DEFAULT '{}',
    cursor              varchar(2000),
    etags               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    status              varchar(8)    NOT NULL DEFAULT 'ACTIVE',
    next_poll_at        timestamptz   NOT NULL,
    last_polled_at      timestamptz,
    lease_until         timestamptz,
    failures            integer       NOT NULL DEFAULT 0,
    backoff_until       timestamptz,
    failing_since       timestamptz,
    last_error          varchar(2000),
    rate_limit          integer,
    rate_remaining      integer,
    rate_resets_at      timestamptz,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT trigger_poll_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT trigger_poll_account_uq UNIQUE (system_id, account_id),
    CONSTRAINT trigger_poll_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_poll_account_fk FOREIGN KEY (account_id, company_id)
        REFERENCES account (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_poll_holder_fk FOREIGN KEY (holder_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_poll_status_ck CHECK (status IN ('ACTIVE', 'IDLE', 'WAITING')),
    CONSTRAINT trigger_poll_offset_ck CHECK (cardinality(offset_keys) <= 500),
    CONSTRAINT trigger_poll_etags_ck CHECK (jsonb_typeof(etags) = 'object'),
    CONSTRAINT trigger_poll_failures_ck CHECK (failures >= 0)
);

CREATE INDEX trigger_poll_account_idx ON trigger_poll (account_id);
CREATE INDEX trigger_poll_holder_idx ON trigger_poll (holder_principal_id);
CREATE INDEX trigger_poll_due_idx ON trigger_poll (next_poll_at) WHERE status = 'ACTIVE';

-- ---- trigger_subject (contract §3.10) ------------------------------------------------------------------------------

--   subject_id                 the system's stable id (Jira's issue id, GitHub's and GitLab's global id)
--   key                        the key people know (SHOP-241, owner/repo#57)
--   kind                       ISSUE or CHANGE_REQUEST
--   labels, assignees          the labels; the assignees' identities at the system
--   text_hash                  the hash of title, description and type (UPDATED)
--   head_sha                   a change request's head commit (CHANGE_REQUEST_UPDATED)
--   created_by_tinfirm_depth   the chain depth of the conversation that created the subject (§5.6); NULL: not Tinfirm's
--   last_comment_id, last_comment_at   the newest comment handled
--   source_updated_at, seen_at the system's last change, and when the poll last read it; unread for 90 days, it goes
CREATE TABLE trigger_subject (
    company_id               uuid          NOT NULL,
    poll_id                  uuid          NOT NULL,
    subject_id               varchar(255)  NOT NULL,
    key                      varchar(255)  NOT NULL,
    kind                     varchar(16)   NOT NULL,
    open                     boolean       NOT NULL,
    status                   varchar(255),
    type                     varchar(255),
    labels                   jsonb         NOT NULL DEFAULT '[]'::jsonb,
    assignees                jsonb         NOT NULL DEFAULT '[]'::jsonb,
    text_hash                char(64),
    head_sha                 varchar(64),
    created_by_tinfirm_depth smallint,
    last_comment_id          varchar(255),
    last_comment_at          timestamptz,
    source_updated_at        timestamptz,
    seen_at                  timestamptz   NOT NULL,
    CONSTRAINT trigger_subject_pk PRIMARY KEY (poll_id, subject_id),
    CONSTRAINT trigger_subject_poll_fk FOREIGN KEY (poll_id, company_id)
        REFERENCES trigger_poll (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_subject_kind_ck CHECK (kind IN ('ISSUE', 'CHANGE_REQUEST')),
    CONSTRAINT trigger_subject_labels_ck CHECK (jsonb_typeof(labels) = 'array'),
    CONSTRAINT trigger_subject_assignees_ck CHECK (jsonb_typeof(assignees) = 'array'),
    CONSTRAINT trigger_subject_depth_ck CHECK (created_by_tinfirm_depth IS NULL OR created_by_tinfirm_depth BETWEEN 0 AND 3)
);

-- Every poll's snapshot of a subject (a write of Tinfirm's updates them all, §5.9); the sweep of unread ones.
CREATE INDEX trigger_subject_key_idx ON trigger_subject (company_id, key);
CREATE INDEX trigger_subject_seen_idx ON trigger_subject (seen_at);

-- ---- trigger_firing (contract §3.11) -------------------------------------------------------------------------------

--   subject_id, subject_key  NULL for a schedule
--   event, value             the first of the trigger's events that happened (ADR-0043), SCHEDULE for a schedule
--   actor                    {id, login, name} at the system
--   comment_external_id      the comment that fired (COMMENTED, MENTIONED)
--   changed_at               when the change happened at the system; due_at a schedule's due time
--   manual_by                run now: the user; never with once
--   once                     a ONCE trigger's firing: at most one per trigger and subject (T10)
--   outcome                  STARTED, CONTINUED, SKIPPED_OPEN, SKIPPED_MISSED, SKIPPED_CHAIN, SKIPPED_RATE,
--                            SKIPPED_AGENT_PAUSED, FAILED; reason the why of a skip or a failure
--   request                  the firing in words, as the turn that takes it shows it in # Request (§5.12): "assigned
--                            to svc-ba by Bob at 10:02", a comment quoted as other people's words
--   subject_summary          a STARTED firing's # Subject for the first turn (open point 12): type, status, labels,
--                            assignees and description, cut to 16 KiB, as other people's words
--   conversation_id, run_id  the conversation and the turn that took it (NULL while it waits for one)
CREATE TABLE trigger_firing (
    id                  uuid          PRIMARY KEY,
    company_id          uuid          NOT NULL,
    trigger_id          uuid          NOT NULL,
    subject_id          varchar(255),
    subject_key         varchar(255),
    event               varchar(32)   NOT NULL,
    value               varchar(255),
    actor               jsonb,
    comment_external_id varchar(255),
    changed_at          timestamptz,
    due_at              timestamptz,
    manual_by           uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    once                boolean       NOT NULL DEFAULT false,
    outcome             varchar(24)   NOT NULL,
    reason              varchar(2000),
    chain_depth         smallint      NOT NULL DEFAULT 0,
    request             text,
    subject_summary     text,
    conversation_id     uuid,
    run_id              uuid,
    created_at          timestamptz   NOT NULL,
    CONSTRAINT trigger_firing_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT trigger_firing_trigger_fk FOREIGN KEY (trigger_id, company_id) REFERENCES trigger (id, company_id),
    CONSTRAINT trigger_firing_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT trigger_firing_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT trigger_firing_event_ck CHECK (event IN ('CREATED', 'UPDATED', 'STATUS_ENTERED', 'LABEL_ADDED',
        'LABEL_REMOVED', 'ASSIGNED', 'MENTIONED', 'COMMENTED', 'CHANGE_REQUEST_OPENED', 'CHANGE_REQUEST_UPDATED',
        'CLOSED', 'SCHEDULE')),
    CONSTRAINT trigger_firing_outcome_ck CHECK (outcome IN ('STARTED', 'CONTINUED', 'SKIPPED_OPEN', 'SKIPPED_MISSED',
        'SKIPPED_CHAIN', 'SKIPPED_RATE', 'SKIPPED_AGENT_PAUSED', 'FAILED')),
    -- A subject's firing names its subject; a schedule's its due time.
    CONSTRAINT trigger_firing_subject_ck CHECK (
        (event = 'SCHEDULE' AND subject_id IS NULL AND subject_key IS NULL)
        OR (event <> 'SCHEDULE' AND subject_id IS NOT NULL AND subject_key IS NOT NULL AND due_at IS NULL)),
    CONSTRAINT trigger_firing_actor_ck CHECK (actor IS NULL OR jsonb_typeof(actor) = 'object'),
    -- Run now never counts as ONCE (§5.6).
    CONSTRAINT trigger_firing_manual_ck CHECK (NOT (once AND manual_by IS NOT NULL)),
    CONSTRAINT trigger_firing_once_subject_ck CHECK (NOT once OR subject_id IS NOT NULL),
    CONSTRAINT trigger_firing_depth_ck CHECK (chain_depth BETWEEN 0 AND 4),
    CONSTRAINT trigger_firing_request_ck CHECK (request IS NULL OR length(request) <= 20000),
    CONSTRAINT trigger_firing_summary_ck CHECK (subject_summary IS NULL OR length(subject_summary) <= 16384),
    -- A firing that waits for a turn names its conversation.
    CONSTRAINT trigger_firing_run_ck CHECK (run_id IS NULL OR conversation_id IS NOT NULL)
);

-- T10: ONCE fires at most once per trigger and subject, across replicas (ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX trigger_firing_once_uq ON trigger_firing (trigger_id, subject_id) WHERE once;
-- GET /triggers/{triggerId}/firings: newest first, keyset; the rate over the last hour.
CREATE INDEX trigger_firing_trigger_idx ON trigger_firing (trigger_id, created_at DESC, id DESC);
CREATE INDEX trigger_firing_conversation_idx ON trigger_firing (conversation_id) WHERE conversation_id IS NOT NULL;
-- The firings that wait for a conversation's next turn (TriggerTurns.waiting).
CREATE INDEX trigger_firing_waiting_idx ON trigger_firing (conversation_id, created_at)
    WHERE run_id IS NULL AND outcome IN ('STARTED', 'CONTINUED');
CREATE INDEX trigger_firing_run_idx ON trigger_firing (run_id) WHERE run_id IS NOT NULL;

-- ---- system_write (contract §3.12) ---------------------------------------------------------------------------------

-- Tinfirm's record of what it wrote (security review, row 10), by every conversation's tool calls and by the outbox:
-- a comment whose id is here is not new, and a write of Tinfirm's updated every poll's snapshot of the subject, so that
-- it raises no event (T9). Kept 90 days.
--
--   kind            COMMENT, TRANSITION, LABEL, CLOSE, ATTACHMENT, REMOTE_LINK, CHANGE_REQUEST, PUSH, ISSUE
--   external_id     a comment's or attachment's id, a change request's number, a head commit
--   written_values  the status entered; the labels added and removed
--   chain_depth     for CHANGE_REQUEST and ISSUE: the creating conversation's
CREATE TABLE system_write (
    id              uuid          PRIMARY KEY,
    company_id      uuid          NOT NULL,
    system_id       uuid          NOT NULL,
    subject_key     varchar(255)  NOT NULL,
    kind            varchar(16)   NOT NULL,
    external_id     varchar(255),
    written_values  jsonb         NOT NULL DEFAULT '{}'::jsonb,
    conversation_id uuid,
    chain_depth     smallint,
    written_at      timestamptz   NOT NULL,
    CONSTRAINT system_write_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT system_write_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT system_write_kind_ck CHECK (kind IN ('COMMENT', 'TRANSITION', 'LABEL', 'CLOSE', 'ATTACHMENT',
        'REMOTE_LINK', 'CHANGE_REQUEST', 'PUSH', 'ISSUE')),
    CONSTRAINT system_write_values_ck CHECK (jsonb_typeof(written_values) = 'object'),
    CONSTRAINT system_write_depth_ck CHECK (
        chain_depth IS NULL OR (kind IN ('CHANGE_REQUEST', 'ISSUE') AND chain_depth BETWEEN 0 AND 3))
);

CREATE INDEX system_write_subject_idx ON system_write (system_id, subject_key);
CREATE INDEX system_write_external_idx ON system_write (system_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX system_write_written_idx ON system_write (written_at);
CREATE INDEX system_write_conversation_idx ON system_write (conversation_id) WHERE conversation_id IS NOT NULL;

-- ---- row-level security and grants ---------------------------------------------------------------------------------

ALTER TABLE trigger_poll ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_poll FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_subject ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_subject FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_firing ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_firing FORCE ROW LEVEL SECURITY;
ALTER TABLE system_write ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_write FORCE ROW LEVEL SECURITY;

CREATE POLICY trigger_poll_tenant ON trigger_poll FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY trigger_subject_tenant ON trigger_subject FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY trigger_firing_tenant ON trigger_firing FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY system_write_tenant ON system_write FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A poll and its snapshots are changed in place and swept; a firing is history, its run set once a turn took it; a
-- write's record is swept after 90 days.
GRANT SELECT, INSERT, UPDATE, DELETE ON trigger_poll, trigger_subject TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON trigger_firing TO ${app_role};
GRANT SELECT, INSERT, DELETE ON system_write TO ${app_role};

-- ---- cross-company lookups (CONVENTIONS §7, as in V004) ------------------------------------------------------------

-- The poller and the schedules (worker replicas) read the due rows of every company: ids and company ids only; the
-- server claims each row again in its company's tenant context (FOR UPDATE SKIP LOCKED). The owner gets SELECT-only
-- policies on the tables the functions read, which the runtime role never matches.
CREATE POLICY trigger_poll_owner_lookup ON trigger_poll FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY trigger_owner_lookup ON trigger FOR SELECT TO CURRENT_USER USING (true);

-- Polls that are ACTIVE, unleased, due and not backing off, the longest due first.
CREATE FUNCTION trigger_poll_due(p_now timestamptz, p_limit integer)
    RETURNS TABLE (poll_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p.id, p.company_id
      FROM ${flyway:defaultSchema}.trigger_poll p
     WHERE p.status = 'ACTIVE'
       AND p.next_poll_at <= p_now
       AND (p.lease_until IS NULL OR p.lease_until < p_now)
       AND (p.backoff_until IS NULL OR p.backoff_until <= p_now)
     ORDER BY p.next_poll_at, p.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- Live, active schedules whose next firing is due, the longest due first.
CREATE FUNCTION trigger_schedule_due(p_now timestamptz, p_limit integer)
    RETURNS TABLE (trigger_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.company_id
      FROM ${flyway:defaultSchema}.trigger t
     WHERE t.source = 'SCHEDULE'
       AND t.status = 'ACTIVE'
       AND t.deleted_at IS NULL
       AND t.next_run_at <= p_now
     ORDER BY t.next_run_at, t.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION trigger_poll_due(timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION trigger_schedule_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trigger_poll_due(timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION trigger_schedule_due(timestamptz, integer) TO ${app_role};
