-- Package 6 (docs/reviews/2026-10-12-package-6-contract.md): the audit's daily check and filters (§3.6, §5.8), and
-- retention, legal holds and erasure (§3.7, §5.9; open points 16, 17, 22, 25), as decided on review (§11).
--
--   audit_check         the daily check of each chain from the previous check's head; the last 400 per chain kept,
--                       and always the last one that anchors the next (it passed, or an OWNER acknowledged it)
--   audit_event         indexes for the filters of GET /audit-events, each ending in seq; nothing hashed changes
--   retention_settings  the OWNER's periods per kind, one row per company; no row: the defaults
--   retention_sweep     when the daily sweep last ran in a company, and what it found
--   legal_hold          a hold per conversation or per principal, open while lifted_at is NULL
--   purge markers       per row, since a CHECK sees one row: each purged text is NULL exactly when its marker is set
--
-- The indexes the package 6 contract also planned for its V022 (reads and access), a number left unused, are
-- created IF NOT EXISTS.

-- ---- audit_check (contract §3.6, §5.8) ----------------------------------------------------------------------------

--   company_id          NULL for the instance chain
--   from_seq            the anchor: the head of the check before it that passed or was acknowledged (0: genesis)
--   failure             BROKEN (an event after the anchor does not verify: first_invalid_seq), SHORTER (the chain
--                       ends before the anchor), HEAD_CHANGED (the event at the anchor has another hash)
--   head_seq, head_hash the chain's last event as the check found it: the next check's anchor once this one passed or
--                       was acknowledged
--   acknowledged_*      an OWNER's acknowledgement of a failure, with their note (on the row only, never audited)
CREATE TABLE audit_check (
    id                uuid        PRIMARY KEY,
    company_id        uuid        REFERENCES company (id),
    checked_at        timestamptz NOT NULL,
    ok                boolean     NOT NULL,
    from_seq          bigint      NOT NULL,
    failure           varchar(16),
    first_invalid_seq bigint,
    head_seq          bigint      NOT NULL,
    head_hash         char(64)    NOT NULL,
    events_checked    bigint      NOT NULL,
    acknowledged_at   timestamptz,
    acknowledged_by   uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    acknowledged_note varchar(1000),
    CONSTRAINT audit_check_seq_ck CHECK (from_seq >= 0 AND head_seq >= 0 AND events_checked >= 0),
    CONSTRAINT audit_check_failure_ck CHECK (
        (ok AND failure IS NULL AND first_invalid_seq IS NULL)
        OR (NOT ok AND failure IN ('BROKEN', 'SHORTER', 'HEAD_CHANGED')
            AND (failure = 'BROKEN') = (first_invalid_seq IS NOT NULL))),
    -- Only a failure is acknowledged; who acknowledged it may be deleted later.
    CONSTRAINT audit_check_acknowledged_ck CHECK (acknowledged_at IS NULL OR NOT ok),
    CONSTRAINT audit_check_acknowledged_by_ck CHECK (
        acknowledged_at IS NOT NULL OR (acknowledged_by IS NULL AND acknowledged_note IS NULL))
);

-- The checks of a chain, newest first.
CREATE INDEX audit_check_chain_idx ON audit_check (company_id, checked_at DESC, id);

ALTER TABLE audit_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_check FORCE ROW LEVEL SECURITY;

-- As audit_event since V016: a company's checks in its context, the instance chain's for instance admins, and every
-- chain's for server-side work (app.system), which checks the chains.
CREATE POLICY audit_check_select ON audit_check FOR SELECT
    USING (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system());
CREATE POLICY audit_check_insert ON audit_check FOR INSERT
    WITH CHECK (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system());
CREATE POLICY audit_check_update ON audit_check FOR UPDATE
    USING (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system())
    WITH CHECK (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system());
CREATE POLICY audit_check_delete ON audit_check FOR DELETE
    USING (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system());

-- A check is written once and acknowledged once; the old ones are pruned.
GRANT SELECT, INSERT, UPDATE, DELETE ON audit_check TO ${app_role};

-- The chains whose last check is older than the cutoff, or that were never checked: ids only, for the daily check on
-- worker replicas, which checks each chain in its own context. NULL is the instance chain.
CREATE POLICY audit_chain_head_owner_lookup ON audit_chain_head FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY audit_check_owner_lookup ON audit_check FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION audit_check_due(p_checked_before timestamptz)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT h.company_id
      FROM ${flyway:defaultSchema}.audit_chain_head h
     WHERE NOT EXISTS (SELECT 1
                         FROM ${flyway:defaultSchema}.audit_check c
                        WHERE c.company_id IS NOT DISTINCT FROM h.company_id
                          AND c.checked_at >= p_checked_before)
     ORDER BY h.company_id NULLS FIRST
     LIMIT 1000
$$;

REVOKE ALL ON FUNCTION audit_check_due(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_check_due(timestamptz) TO ${app_role};

-- ---- audit_event: the filters' indexes (contract §3.6) ----------------------------------------------------------

CREATE INDEX IF NOT EXISTS audit_event_action_idx ON audit_event (company_id, action, seq);
CREATE INDEX IF NOT EXISTS audit_event_target_idx ON audit_event (company_id, target_type, target_id, seq);
CREATE INDEX IF NOT EXISTS audit_event_actor_idx ON audit_event (company_id, actor_type, actor_id, seq);
CREATE INDEX IF NOT EXISTS audit_event_occurred_idx ON audit_event (company_id, occurred_at, seq);
CREATE INDEX IF NOT EXISTS audit_event_conversation_idx ON audit_event (company_id, (details ->> 'conversationId'), seq);
CREATE INDEX IF NOT EXISTS audit_event_principal_idx ON audit_event (company_id, (details ->> 'principalId'), seq);

-- ---- retention_settings (contract §3.7, open point 16) ----------------------------------------------------------

--   run_events_days     7 to 3650, from the run's end: its transcript
--   chat_content_days   1 to 3650 and at most content_days, from the end of a chat's or a webhook's conversation:
--                       its messages and decisions' texts
--   content_days        30 to 3650, or NULL (keep), from the conversation's end: messages, titles, decisions' texts,
--                       runs' texts, outputs' titles
--   files_days          7 to 3650, or NULL (keep), from the conversation's end: attachments' content
CREATE TABLE retention_settings (
    company_id        uuid        PRIMARY KEY REFERENCES company (id),
    run_events_days   integer     NOT NULL DEFAULT 90,
    chat_content_days integer     NOT NULL DEFAULT 30,
    content_days      integer              DEFAULT 365,
    files_days        integer              DEFAULT 365,
    updated_at        timestamptz NOT NULL,
    updated_by        uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT retention_run_events_ck CHECK (run_events_days BETWEEN 7 AND 3650),
    CONSTRAINT retention_chat_content_ck CHECK (chat_content_days BETWEEN 1 AND 3650
        AND (content_days IS NULL OR chat_content_days <= content_days)),
    CONSTRAINT retention_content_ck CHECK (content_days IS NULL OR content_days BETWEEN 30 AND 3650),
    CONSTRAINT retention_files_ck CHECK (files_days IS NULL OR files_days BETWEEN 7 AND 3650)
);

-- When the daily sweep last ran in a company: the companies due come from retention_due below.
--   open_older   the open conversations it found older than their period (open point 25)
CREATE TABLE retention_sweep (
    company_id uuid        PRIMARY KEY REFERENCES company (id),
    swept_at   timestamptz NOT NULL,
    open_older integer     NOT NULL DEFAULT 0,
    CONSTRAINT retention_sweep_open_ck CHECK (open_older >= 0)
);

ALTER TABLE retention_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE retention_sweep ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_sweep FORCE ROW LEVEL SECURITY;

CREATE POLICY retention_settings_tenant ON retention_settings FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY retention_sweep_tenant ON retention_sweep FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Changed in place, never removed.
GRANT SELECT, INSERT, UPDATE ON retention_settings, retention_sweep TO ${app_role};

-- The companies whose last sweep is older than the cutoff, or that were never swept: ids only.
CREATE POLICY company_owner_lookup ON company FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY retention_sweep_owner_lookup ON retention_sweep FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION retention_due(p_swept_before timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT c.id
      FROM ${flyway:defaultSchema}.company c
      LEFT JOIN ${flyway:defaultSchema}.retention_sweep s ON s.company_id = c.id
     WHERE s.swept_at IS NULL OR s.swept_at < p_swept_before
     ORDER BY s.swept_at NULLS FIRST, c.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION retention_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retention_due(timestamptz, integer) TO ${app_role};

-- ---- legal_hold (contract §3.7, open point 22) ------------------------------------------------------------------

-- A hold on a conversation (all of it, its runs' events included) or on a member's principal (their personal
-- conversations, and what they said, added and decided in others). The reason stays on the row: never in the audit.
CREATE TABLE legal_hold (
    id              uuid         PRIMARY KEY,
    company_id      uuid         NOT NULL,
    conversation_id uuid,
    principal_id    uuid,
    reason          varchar(500) NOT NULL,
    set_at          timestamptz  NOT NULL,
    set_by          uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    lifted_at       timestamptz,
    lifted_by       uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT legal_hold_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT legal_hold_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT legal_hold_principal_fk FOREIGN KEY (principal_id, company_id) REFERENCES principal (id, company_id),
    CONSTRAINT legal_hold_target_ck CHECK ((conversation_id IS NULL) <> (principal_id IS NULL)),
    CONSTRAINT legal_hold_reason_ck CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT legal_hold_lifted_ck CHECK (lifted_at IS NOT NULL OR lifted_by IS NULL)
);

-- The open holds of a company and of each target; the lifted ones by their lift, for their deletion after 400 days.
CREATE INDEX legal_hold_open_idx ON legal_hold (company_id, set_at, id) WHERE lifted_at IS NULL;
CREATE INDEX legal_hold_conversation_idx ON legal_hold (conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX legal_hold_principal_idx ON legal_hold (principal_id) WHERE principal_id IS NOT NULL;
CREATE INDEX legal_hold_lifted_idx ON legal_hold (company_id, lifted_at) WHERE lifted_at IS NOT NULL;

ALTER TABLE legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE legal_hold FORCE ROW LEVEL SECURITY;

CREATE POLICY legal_hold_tenant ON legal_hold FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Set, lifted in place, and deleted 400 days after its lift.
GRANT SELECT, INSERT, UPDATE, DELETE ON legal_hold TO ${app_role};

-- ---- purge markers (contract §3.7) ------------------------------------------------------------------------------

-- message: the body, NULL exactly when purged_at is set. The API shows a purged message as removed; a turn's prompt
-- shows the marker in its place.
ALTER TABLE message ADD COLUMN purged_at timestamptz;
ALTER TABLE message ALTER COLUMN body DROP NOT NULL;
ALTER TABLE message DROP CONSTRAINT message_body_ck;
ALTER TABLE message ADD CONSTRAINT message_body_ck CHECK (
    (purged_at IS NULL AND body IS NOT NULL AND length(body) BETWEEN 1 AND 100000)
    OR (purged_at IS NOT NULL AND body IS NULL));
CREATE INDEX message_unpurged_idx ON message (conversation_id) WHERE purged_at IS NULL;

-- decision: the question, options, reason, answer, option, note and result, and a tool call's arguments and words in
-- the subject. Its kind, status, outcome, scope, access and who decided stay.
ALTER TABLE decision ADD COLUMN purged_at timestamptz;
ALTER TABLE decision DROP CONSTRAINT decision_shape_ck;
ALTER TABLE decision ADD CONSTRAINT decision_shape_ck CHECK (
    (kind = 'QUESTION' AND (question IS NOT NULL) = (purged_at IS NULL) AND subject IS NULL)
    OR (kind = 'APPROVAL' AND question IS NULL AND options IS NULL AND subject IS NOT NULL));
ALTER TABLE decision ADD CONSTRAINT decision_purged_ck CHECK (purged_at IS NULL OR (
    question IS NULL AND options IS NULL AND reason IS NULL AND answer IS NULL AND option IS NULL AND note IS NULL
    AND result IS NULL AND (subject IS NULL OR (subject -> 'arguments' IS NULL AND subject -> 'what' IS NULL))));

-- output: the title; its kind and link stay.
ALTER TABLE output ADD COLUMN purged_at timestamptz;
ALTER TABLE output ALTER COLUMN title DROP NOT NULL;
ALTER TABLE output DROP CONSTRAINT output_title_ck;
ALTER TABLE output ADD CONSTRAINT output_title_ck CHECK (
    (purged_at IS NULL AND title IS NOT NULL AND length(btrim(title)) BETWEEN 1 AND 200)
    OR (purged_at IS NOT NULL AND title IS NULL));

-- attachment: the content and the note. Its name, size (the original's), hash and type stay.
ALTER TABLE attachment ADD COLUMN content_purged_at timestamptz;
ALTER TABLE attachment ALTER COLUMN content DROP NOT NULL;
ALTER TABLE attachment DROP CONSTRAINT attachment_size_ck;
ALTER TABLE attachment ADD CONSTRAINT attachment_size_ck CHECK (size_bytes >= 0 AND (
    (content_purged_at IS NULL AND content IS NOT NULL AND size_bytes = octet_length(content))
    OR (content_purged_at IS NOT NULL AND content IS NULL AND note IS NULL)));
CREATE INDEX attachment_unpurged_idx ON attachment (conversation_id) WHERE content_purged_at IS NULL;

-- run: its texts (the summary, the failure's message, the finish result's steps and questions) and, apart, its
-- events. Its status, hosts, payer, usage and counters stay.
ALTER TABLE run ADD COLUMN texts_purged_at timestamptz;
ALTER TABLE run ADD COLUMN events_purged_at timestamptz;
ALTER TABLE run ADD CONSTRAINT run_texts_purged_ck CHECK (texts_purged_at IS NULL OR (
    summary IS NULL AND failure_message IS NULL
    AND (result_next_steps IS NULL OR result_next_steps = '[]'::jsonb)
    AND (result_open_questions IS NULL OR result_open_questions = '[]'::jsonb)));
-- The ended runs whose events are kept, by their end, for the sweep.
CREATE INDEX run_events_kept_idx ON run (company_id, finished_at, id)
    WHERE events_purged_at IS NULL AND finished_at IS NOT NULL;

-- conversation: the title (title_purged_at); the content's removal under retention (content_purged_at) or by an
-- erasure (erased_at), for the tombstone's line. The latest turn's result and the subject's title go with the content.
ALTER TABLE conversation ADD COLUMN title_purged_at timestamptz;
ALTER TABLE conversation ADD COLUMN content_purged_at timestamptz;
ALTER TABLE conversation ADD COLUMN erased_at timestamptz;
ALTER TABLE conversation ALTER COLUMN title DROP NOT NULL;
ALTER TABLE conversation DROP CONSTRAINT conversation_title_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_title_ck CHECK (
    (title_purged_at IS NULL AND title IS NOT NULL AND length(btrim(title)) BETWEEN 1 AND 200)
    OR (title_purged_at IS NOT NULL AND title IS NULL));
ALTER TABLE conversation ADD CONSTRAINT conversation_content_purged_ck CHECK (
    (content_purged_at IS NULL AND erased_at IS NULL)
    OR (title_purged_at IS NOT NULL AND result_summary IS NULL AND subject_title IS NULL
        AND (result_next_steps IS NULL OR result_next_steps = '[]'::jsonb)
        AND (result_open_questions IS NULL OR result_open_questions = '[]'::jsonb)));
-- The ended conversations whose content is kept, by their end, for the sweep; the open ones by their start.
CREATE INDEX conversation_content_kept_idx ON conversation (company_id, ended_at, id)
    WHERE state = 'ENDED' AND content_purged_at IS NULL;
CREATE INDEX conversation_open_idx ON conversation (company_id, created_at, id) WHERE state = 'OPEN';
