-- Triggers (the package 4 contract, docs/reviews/2026-10-12-package-4-contract.md §3.1 to §3.8, §4 and §11):
-- company automation. A trigger is a standing rule on a system, or a schedule: when an event happens to an issue or a
-- change request, or a schedule is due, an agent does an instruction with an account per system and the model access
-- its author chose; its team sees its conversations and decides their approvals.
--
--   trigger            the rule, its author, team, payer, results, firing limit, status and health (§3.1)
--   trigger_account    the account table: one account per system the trigger needs (§3.2)
--   trigger_setting    the company's firing limits, default and maximum (§11, open point 15)
--   agent_settings     gains trigger_id as its other owner: a trigger's settings (§3.3)
--   conversation       gains trigger_id, author_principal_id, chain_depth; person_principal_id becomes nullable (§3.4)
--   conversation_binding  how gains FIXED; reasons NOT_ENTITLED (waiting) and ACCOUNT_REMOVED (gone) (§3.5, §11)
--   message            kind gains EXTERNAL, with external_id and external_author (§3.6)
--   run                gains trigger_id; turn reasons EVENT, REVIEW_RETURNED; queue reason TRIGGER_PAUSED (§3.7)
--   decision           approver_group_id beside a nullable approver_principal_id; subject RESULT; decided_by_name,
--                      decided_by_message_id (§3.8)
--   conversation_rebind  the one way a reassigned trigger's waiting conversations take its new table and payer (§11)
--
-- The global lock order (CONVENTIONS §10, extended by package 4): a trigger's row first, then its conversations (in
-- the order of their ids), then their runs, bindings and decisions, and the company's audit_chain_head last. Trigger
-- and conversation rows are locked FOR NO KEY UPDATE, so that the foreign keys' checks of the rows naming them (a new
-- conversation of the trigger, a firing, a run) never wait on them.
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), a composite UNIQUE (id, company_id) that composite foreign keys point at, and grants to the
-- runtime role ${app_role}, which owns nothing. The schema owner runs this file.

-- ---- trigger (contract §3.1) ---------------------------------------------------------------------------------------

--   name                  1 to 100 characters, unique among the company's live triggers ignoring case
--                         (DUPLICATE_TRIGGER_NAME)
--   source, system_id     TRACKER with a company system of kind JIRA_DC, JIRA_CLOUD, GITHUB or GITLAB, or SCHEDULE
--                         without a system (open point 5). Both fixed. No foreign key on the system: a removed system
--                         deletes its triggers (system.deleted), whose rows keep naming it as their history; the
--                         trigger checks it at insert
--   events                1 to 5 {event, value} for TRACKER (ADR-0043); none for SCHEDULE, whose event is SCHEDULE
--   subject               ANY, ISSUE or CHANGE_REQUEST
--   scope                 a Jira project key, owner/repo or a GitLab path; NULL: everything the source account sees
--   conditions            at most 5 condition groups (ADR-0043), each with authors PEOPLE or TINFIRM (§5.5)
--   repeat                ONCE or EVERY_TIME
--   schedule_*,           a SCHEDULE's (ADR-0032): five cron fields at most every 15 minutes, an IANA zone; overlap
--   overlap, catch_up,    SKIP or CREATE; catch_up NONE or ONE; the next due time, the last one and its outcome
--   next_run_at, ...
--   agent_id              the agent, fixed: a trigger for another agent is another trigger
--   instruction           what the job is, 1 to 20 000 characters, written by the author
--   author_principal_id   a member's principal (author_kind is always MEMBER): the creator, or whom an admin reassigned
--                         it to
--   team_group_id         sees its conversations, decides their approvals, runs it now; NULL: the author alone
--   payer_principal_id    the author, or a service identity the author may use (T1); NULL only once deleted, when its
--                         service identity went
--   results               {comment, review, onSuccess, onFailure}
--   max_firings_per_hour  at least 1; at most the company's maximum (trigger_setting, the service:
--                         TRIGGER_LIMIT_TOO_HIGH)
--   status, paused_*      ACTIVE or PAUSED with its reason: USER, AUTHOR_LEFT, NOT_ENTITLED, ACCOUNT_REMOVED,
--                         SYSTEM_REMOVED, ACCOUNT_BROKEN (§11, open point 9)
--   waiting_reason,       its health, for lists and the inbox; NULL when nothing waits
--   waiting_since
--   armed_at              a change before it fires nothing (T16): the creation, each resume, each reassignment
--   missed_count,         changes read too late and firings over the rate, since the last resume
--   rate_skipped_count
--   open_events_*         the author's acknowledgement of the editor's warning (§5.8)
--   deleted_at, ...       soft delete: the row stays for its conversations' history
CREATE TABLE trigger (
    id                          uuid          PRIMARY KEY,
    company_id                  uuid          NOT NULL REFERENCES company (id),
    name                        varchar(100)  NOT NULL,
    source                      varchar(8)    NOT NULL,
    system_id                   uuid,
    events                      jsonb         NOT NULL DEFAULT '[]'::jsonb,
    subject                     varchar(16)   NOT NULL DEFAULT 'ANY',
    scope                       varchar(255),
    conditions                  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    repeat                      varchar(16)   NOT NULL DEFAULT 'EVERY_TIME',
    schedule_cron               varchar(100),
    schedule_zone               varchar(64),
    overlap                     varchar(8),
    catch_up                    varchar(8),
    next_run_at                 timestamptz,
    last_run_at                 timestamptz,
    last_outcome                varchar(24),
    agent_id                    uuid          NOT NULL,
    instruction                 text          NOT NULL,
    author_principal_id         uuid          NOT NULL,
    author_kind                 varchar(8)    NOT NULL DEFAULT 'MEMBER',
    team_group_id               uuid,
    payer_principal_id          uuid,
    results                     jsonb         NOT NULL DEFAULT '{"comment": true, "review": false, "onSuccess": [], "onFailure": []}'::jsonb,
    max_firings_per_hour        integer       NOT NULL DEFAULT 30,
    status                      varchar(8)    NOT NULL DEFAULT 'ACTIVE',
    paused_reason               varchar(16),
    paused_at                   timestamptz,
    paused_by                   uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    waiting_reason              varchar(24),
    waiting_since               timestamptz,
    armed_at                    timestamptz   NOT NULL,
    missed_count                integer       NOT NULL DEFAULT 0,
    rate_skipped_count          integer       NOT NULL DEFAULT 0,
    open_events_acknowledged_at timestamptz,
    open_events_acknowledged_by uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    deleted_at                  timestamptz,
    deleted_cause               varchar(32),
    deleted_by                  uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    created_at                  timestamptz   NOT NULL,
    updated_at                  timestamptz   NOT NULL,
    created_by                  uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT trigger_id_company_uq UNIQUE (id, company_id),
    -- The target of agent_settings' and conversation's keys: a trigger's settings and conversations are of its agent.
    CONSTRAINT trigger_id_agent_uq UNIQUE (id, agent_id, company_id),
    CONSTRAINT trigger_agent_fk FOREIGN KEY (agent_id, company_id) REFERENCES agent (id, company_id),
    CONSTRAINT trigger_author_kind_ck CHECK (author_kind = 'MEMBER'),
    CONSTRAINT trigger_author_fk FOREIGN KEY (author_principal_id, company_id, author_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT trigger_payer_fk FOREIGN KEY (payer_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE SET NULL (payer_principal_id),
    CONSTRAINT trigger_team_fk FOREIGN KEY (team_group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE SET NULL (team_group_id),
    CONSTRAINT trigger_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT trigger_source_kind_ck CHECK (source IN ('TRACKER', 'SCHEDULE')),
    CONSTRAINT trigger_subject_ck CHECK (subject IN ('ANY', 'ISSUE', 'CHANGE_REQUEST')),
    CONSTRAINT trigger_repeat_ck CHECK (repeat IN ('ONCE', 'EVERY_TIME')),
    CONSTRAINT trigger_scope_ck CHECK (scope IS NULL OR length(btrim(scope)) BETWEEN 1 AND 255),
    CONSTRAINT trigger_events_ck CHECK (jsonb_typeof(events) = 'array' AND jsonb_array_length(events) <= 5),
    CONSTRAINT trigger_conditions_ck CHECK (
        jsonb_typeof(conditions) = 'array' AND jsonb_array_length(conditions) <= 5),
    CONSTRAINT trigger_results_ck CHECK (jsonb_typeof(results) = 'object'),
    -- A tracker trigger has its system and one to five events, and no schedule; a schedule has its cron, zone,
    -- overlap and catch-up, and no system, events or scope. A tracker trigger's system id stays for its history.
    CONSTRAINT trigger_shape_ck CHECK (
        (source = 'TRACKER' AND system_id IS NOT NULL AND jsonb_array_length(events) BETWEEN 1 AND 5
         AND schedule_cron IS NULL AND schedule_zone IS NULL AND overlap IS NULL AND catch_up IS NULL
         AND next_run_at IS NULL)
        OR (source = 'SCHEDULE' AND system_id IS NULL AND jsonb_array_length(events) = 0 AND scope IS NULL
            AND schedule_cron IS NOT NULL AND schedule_zone IS NOT NULL AND overlap IS NOT NULL
            AND catch_up IS NOT NULL)),
    CONSTRAINT trigger_cron_ck CHECK (schedule_cron IS NULL OR length(btrim(schedule_cron)) BETWEEN 9 AND 100),
    CONSTRAINT trigger_zone_ck CHECK (schedule_zone IS NULL OR length(schedule_zone) BETWEEN 1 AND 64),
    CONSTRAINT trigger_overlap_ck CHECK (overlap IS NULL OR overlap IN ('SKIP', 'CREATE')),
    CONSTRAINT trigger_catch_up_ck CHECK (catch_up IS NULL OR catch_up IN ('NONE', 'ONE')),
    CONSTRAINT trigger_last_outcome_ck CHECK (last_outcome IS NULL OR last_outcome IN (
        'STARTED', 'CONTINUED', 'SKIPPED_OPEN', 'SKIPPED_MISSED', 'SKIPPED_CHAIN', 'SKIPPED_RATE',
        'SKIPPED_AGENT_PAUSED', 'FAILED')),
    CONSTRAINT trigger_instruction_ck CHECK (length(btrim(instruction)) BETWEEN 1 AND 20000),
    -- The payer goes only with a deleted trigger's service identity.
    CONSTRAINT trigger_payer_ck CHECK (payer_principal_id IS NOT NULL OR deleted_at IS NOT NULL),
    CONSTRAINT trigger_rate_ck CHECK (max_firings_per_hour BETWEEN 1 AND 10000),
    CONSTRAINT trigger_status_ck CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT trigger_paused_reason_ck CHECK (paused_reason IS NULL OR paused_reason IN (
        'USER', 'AUTHOR_LEFT', 'NOT_ENTITLED', 'ACCOUNT_REMOVED', 'SYSTEM_REMOVED', 'ACCOUNT_BROKEN')),
    CONSTRAINT trigger_paused_ck CHECK (
        (status = 'PAUSED') = (paused_reason IS NOT NULL AND paused_at IS NOT NULL)
        AND (paused_by IS NULL OR status = 'PAUSED')),
    CONSTRAINT trigger_waiting_reason_ck CHECK (waiting_reason IS NULL OR waiting_reason IN (
        'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'SUSPENDED', 'SYSTEM_UNREACHABLE', 'SCOPE_UNREADABLE',
        'GATEWAY_NOT_CONFIGURED')),
    CONSTRAINT trigger_waiting_ck CHECK ((waiting_reason IS NULL) = (waiting_since IS NULL)),
    CONSTRAINT trigger_counts_ck CHECK (missed_count >= 0 AND rate_skipped_count >= 0),
    CONSTRAINT trigger_acknowledged_ck CHECK (
        open_events_acknowledged_by IS NULL OR open_events_acknowledged_at IS NOT NULL),
    CONSTRAINT trigger_deleted_ck CHECK (
        (deleted_cause IS NULL OR deleted_at IS NOT NULL) AND (deleted_by IS NULL OR deleted_at IS NOT NULL)),
    CONSTRAINT trigger_deleted_cause_ck CHECK (deleted_cause IS NULL OR deleted_cause IN (
        'USER', 'agent.deleted', 'system.deleted'))
);

-- DUPLICATE_TRIGGER_NAME among the live triggers; a deleted trigger frees its name.
CREATE UNIQUE INDEX trigger_name_uq ON trigger (company_id, lower(name)) WHERE deleted_at IS NULL;
-- GET /triggers: keyset on (name, id) over the live triggers.
CREATE INDEX trigger_page_idx ON trigger (company_id, name, id) WHERE deleted_at IS NULL;
-- A system's triggers (at most 100 live ones: the service), its removal; an agent's, its pause and deletion.
CREATE INDEX trigger_system_idx ON trigger (company_id, system_id) WHERE system_id IS NOT NULL;
CREATE INDEX trigger_agent_idx ON trigger (agent_id);
-- An author's triggers (their leaving, the gate on package 1's events); a payer's; a team's.
CREATE INDEX trigger_author_idx ON trigger (company_id, author_principal_id);
CREATE INDEX trigger_payer_idx ON trigger (payer_principal_id) WHERE payer_principal_id IS NOT NULL;
CREATE INDEX trigger_team_idx ON trigger (team_group_id) WHERE team_group_id IS NOT NULL;
-- Due schedules (trigger_schedule_due, V012).
CREATE INDEX trigger_schedule_due_idx ON trigger (next_run_at)
    WHERE source = 'SCHEDULE' AND status = 'ACTIVE' AND deleted_at IS NULL;

-- T1 and the trigger's fixed parts, which no CHECK can say:
--   a tracker trigger's system is a company system of a tracker kind (SYSTEM_KIND_INVALID before it, in the service);
--   T1: the payer is the author or a service identity, never another member;
--   the source, the system, the agent and the company never change, and a deleted trigger stays deleted.
CREATE FUNCTION trigger_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.source = 'TRACKER' AND NOT EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.owner_principal_id IS NULL
               AND s.kind IN ('JIRA_DC', 'JIRA_CLOUD', 'GITHUB', 'GITLAB')) THEN
        RAISE EXCEPTION 'A tracker trigger belongs to a company system of kind JIRA_DC, JIRA_CLOUD, GITHUB or GITLAB'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_source_ck';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.source IS DISTINCT FROM OLD.source OR NEW.system_id IS DISTINCT FROM OLD.system_id
           OR NEW.agent_id IS DISTINCT FROM OLD.agent_id OR NEW.company_id IS DISTINCT FROM OLD.company_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'A trigger''s source, system and agent are fixed'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_fixed_ck';
        END IF;
        IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
            RAISE EXCEPTION 'A deleted trigger stays deleted'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_deleted_final_ck';
        END IF;
    END IF;
    IF NEW.payer_principal_id IS NOT NULL AND NEW.payer_principal_id <> NEW.author_principal_id AND NOT EXISTS (
            SELECT 1 FROM principal p
             WHERE p.id = NEW.payer_principal_id AND p.company_id = NEW.company_id AND p.kind = 'SERVICE') THEN
        RAISE EXCEPTION 'A trigger is paid by its author or by a service identity, never by another member'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_payer_holder_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trigger_ck_trg BEFORE INSERT OR UPDATE ON trigger
    FOR EACH ROW EXECUTE FUNCTION trigger_ck();

-- ---- trigger_account (contract §3.2) -------------------------------------------------------------------------------

-- The account table: one account per system the trigger needs (T2), at most 100 (the service).
--
--   account_id           the account the trigger's conversations bind: the holder's on the system. NULL once the
--                        account went (ON DELETE SET NULL), and only while the trigger is paused (ACCOUNT_REMOVED,
--                        AUTHOR_LEFT) or deleted, until its author or an admin names another or removes the row
--   holder_principal_id  whose account: the trigger's author or a service identity (T1); never another member
--   external_id          the account's identity on the system when saved; the source row has one (T2)
--   filled_from          CHOICE (the editor's one choice filled it) or ROW (the author changed it)
-- There is no grant: grants serve personal conversations only.
CREATE TABLE trigger_account (
    company_id          uuid         NOT NULL,
    trigger_id          uuid         NOT NULL,
    system_id           uuid         NOT NULL,
    account_id          uuid,
    holder_principal_id uuid         NOT NULL,
    external_id         varchar(255),
    filled_from         varchar(8)   NOT NULL,
    saved_at            timestamptz  NOT NULL,
    saved_by            uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    -- T2: one account per system per trigger.
    CONSTRAINT trigger_account_pk PRIMARY KEY (trigger_id, system_id),
    CONSTRAINT trigger_account_trigger_fk FOREIGN KEY (trigger_id, company_id)
        REFERENCES trigger (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_account_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_account_account_fk FOREIGN KEY (account_id, company_id)
        REFERENCES account (id, company_id) ON DELETE SET NULL (account_id),
    CONSTRAINT trigger_account_holder_fk FOREIGN KEY (holder_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_account_filled_from_ck CHECK (filled_from IN ('CHOICE', 'ROW'))
);

CREATE INDEX trigger_account_system_idx ON trigger_account (company_id, system_id);
CREATE INDEX trigger_account_account_idx ON trigger_account (account_id) WHERE account_id IS NOT NULL;
CREATE INDEX trigger_account_holder_idx ON trigger_account (holder_principal_id);

-- T1 and T3 on a row: the holder is the trigger's author or a service identity; the account is the holder's on the
-- row's system; a service identity's row is refused while the trigger's settings add the author's own MCP servers
-- (T3: they act only while every row holds the author's own account).
CREATE FUNCTION trigger_account_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_author uuid;
BEGIN
    SELECT t.author_principal_id INTO v_author
      FROM trigger t WHERE t.id = NEW.trigger_id AND t.company_id = NEW.company_id;
    IF NEW.holder_principal_id <> v_author AND NOT EXISTS (
            SELECT 1 FROM principal p
             WHERE p.id = NEW.holder_principal_id AND p.company_id = NEW.company_id AND p.kind = 'SERVICE') THEN
        RAISE EXCEPTION 'A trigger acts with its author''s own accounts or a service identity''s, never another member''s'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_account_holder_ck';
    END IF;
    IF NEW.account_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM account a
             WHERE a.id = NEW.account_id AND a.company_id = NEW.company_id
               AND a.principal_id = NEW.holder_principal_id AND a.system_id = NEW.system_id) THEN
        RAISE EXCEPTION 'A row names its holder''s account on its system'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_account_holder_ck';
    END IF;
    IF NEW.holder_principal_id <> v_author AND EXISTS (
            SELECT 1 FROM agent_settings st JOIN agent_settings_system ss ON ss.settings_id = st.id
             WHERE st.trigger_id = NEW.trigger_id AND ss.state = 'ADDED') THEN
        RAISE EXCEPTION 'The author''s own MCP servers act only while every row holds the author''s own account'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trigger_account_ck_trg BEFORE INSERT OR UPDATE ON trigger_account
    FOR EACH ROW EXECUTE FUNCTION trigger_account_ck();

-- T1 and T2 over the whole table, at the commit (a trigger is inserted before its rows; a reassignment changes the
-- author and the rows together; an account's removal empties its row before the trigger is paused, in the same
-- transaction). For a live trigger:
--   T2: a tracker trigger has a row on its source system with an identity;
--   T1: every row's holder is the author or a service identity;
--   a row without an account only while the trigger is paused.
CREATE FUNCTION trigger_table_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_trigger_id uuid;
    v_trigger    record;
BEGIN
    IF TG_TABLE_NAME = 'trigger' THEN
        v_trigger_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        v_trigger_id := OLD.trigger_id;
    ELSE
        v_trigger_id := NEW.trigger_id;
    END IF;
    SELECT t.id, t.company_id, t.source, t.system_id, t.author_principal_id, t.status, t.deleted_at
      INTO v_trigger
      FROM trigger t WHERE t.id = v_trigger_id;
    IF NOT FOUND OR v_trigger.deleted_at IS NOT NULL THEN
        RETURN NULL;
    END IF;
    IF v_trigger.source = 'TRACKER' AND NOT EXISTS (
            SELECT 1 FROM trigger_account a
             WHERE a.trigger_id = v_trigger.id AND a.system_id = v_trigger.system_id AND a.external_id IS NOT NULL) THEN
        RAISE EXCEPTION 'A tracker trigger has an account with an identity on its source system'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_source_account_ck';
    END IF;
    IF EXISTS (
            SELECT 1 FROM trigger_account a
             WHERE a.trigger_id = v_trigger.id AND a.holder_principal_id <> v_trigger.author_principal_id
               AND NOT EXISTS (SELECT 1 FROM principal p
                                WHERE p.id = a.holder_principal_id AND p.company_id = a.company_id
                                  AND p.kind = 'SERVICE')) THEN
        RAISE EXCEPTION 'A trigger acts with its author''s own accounts or a service identity''s, never another member''s'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_account_holder_ck';
    END IF;
    IF v_trigger.status <> 'PAUSED' AND EXISTS (
            SELECT 1 FROM trigger_account a WHERE a.trigger_id = v_trigger.id AND a.account_id IS NULL) THEN
        RAISE EXCEPTION 'A trigger whose account went is paused until its author or an admin names another'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_account_paused_ck';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trigger_table_ck_trg AFTER INSERT OR UPDATE ON trigger
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trigger_table_ck();
CREATE CONSTRAINT TRIGGER trigger_account_table_ck_trg AFTER INSERT OR UPDATE OR DELETE ON trigger_account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trigger_table_ck();

-- ---- the company's firing limits (contract §11, open point 15) ------------------------------------------------------

-- The default and the maximum of a trigger's max_firings_per_hour, set by admins (PUT /settings); without a row the
-- instance's configuration (TINFIRM_TRIGGER_FIRINGS_PER_HOUR_DEFAULT, _MAX) holds.
CREATE TABLE trigger_setting (
    company_id               uuid        PRIMARY KEY REFERENCES company (id),
    firings_per_hour_default integer     NOT NULL,
    firings_per_hour_max     integer     NOT NULL,
    updated_at               timestamptz NOT NULL,
    updated_by               uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT trigger_setting_limits_ck CHECK (
        firings_per_hour_default >= 1 AND firings_per_hour_max <= 10000
        AND firings_per_hour_default <= firings_per_hour_max)
);

-- ---- a trigger's settings (contract §3.3) --------------------------------------------------------------------------

-- agent_settings gains trigger_id as its other owner: exactly one of principal_id and trigger_id. The row is made with
-- the trigger and goes with it; its agent is the trigger's (the composite key). accepted_version is the agent's version
-- the author accepted for the trigger.
ALTER TABLE agent_settings ALTER COLUMN principal_id DROP NOT NULL;
ALTER TABLE agent_settings ADD COLUMN trigger_id uuid;
ALTER TABLE agent_settings ADD CONSTRAINT agent_settings_owner_ck CHECK ((principal_id IS NULL) <> (trigger_id IS NULL));
ALTER TABLE agent_settings ADD CONSTRAINT agent_settings_trigger_uq UNIQUE (trigger_id);
ALTER TABLE agent_settings ADD CONSTRAINT agent_settings_trigger_fk FOREIGN KEY (trigger_id, agent_id, company_id)
    REFERENCES trigger (id, agent_id, company_id) ON DELETE CASCADE;

-- I4 and T3: settings narrow, never widen. OFF only for a system the agent lists; ADDED only for an MCP server the
-- settings' owner owns: the person, or the trigger's author, and for a trigger only while every row of its table holds
-- the author's own account.
CREATE OR REPLACE FUNCTION agent_settings_system_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_agent     uuid;
    v_principal uuid;
    v_trigger   uuid;
BEGIN
    SELECT st.agent_id, coalesce(st.principal_id, t.author_principal_id), st.trigger_id
      INTO v_agent, v_principal, v_trigger
      FROM agent_settings st
      LEFT JOIN trigger t ON t.id = st.trigger_id
     WHERE st.id = NEW.settings_id AND st.company_id = NEW.company_id;
    IF NEW.state = 'OFF' AND NOT EXISTS (
            SELECT 1 FROM agent_system a WHERE a.agent_id = v_agent AND a.system_id = NEW.system_id) THEN
        RAISE EXCEPTION 'Settings switch off only a system the agent lists'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    IF NEW.state = 'ADDED' AND NOT EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.kind = 'MCP'
               AND s.owner_principal_id = v_principal) THEN
        RAISE EXCEPTION 'Settings add only the person''s own MCP server'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    IF NEW.state = 'ADDED' AND v_trigger IS NOT NULL AND EXISTS (
            SELECT 1 FROM trigger_account a WHERE a.trigger_id = v_trigger AND a.holder_principal_id <> v_principal) THEN
        RAISE EXCEPTION 'A trigger adds its author''s own MCP servers only while every row holds the author''s own account'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    RETURN NEW;
END
$$;

-- ---- conversation (contract §3.4) ----------------------------------------------------------------------------------

--   person_principal_id   NULL for a trigger's conversation
--   trigger_id            the trigger; NULL for a personal conversation
--   author_principal_id   a trigger's conversation: the trigger's author when it started, fixed but by
--                         conversation_rebind. Its bindings act for this principal, and it is its runs' acting principal
--   place_ref             TRACKER_ISSUE: {systemId, subjectId, key, kind, url}, subjectId the system's stable id;
--                         SCHEDULE: {triggerId, firingId, dueAt}
--   chain_depth           0, or one more than the creating conversation's for a conversation fired by a subject Tinfirm
--                         created (§5.6); at most 3
ALTER TABLE conversation ALTER COLUMN person_principal_id DROP NOT NULL;
ALTER TABLE conversation ADD COLUMN trigger_id uuid;
ALTER TABLE conversation ADD COLUMN author_principal_id uuid;
ALTER TABLE conversation ADD COLUMN author_kind varchar(8) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE conversation ADD COLUMN chain_depth smallint NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD CONSTRAINT conversation_trigger_fk FOREIGN KEY (trigger_id, agent_id, company_id)
    REFERENCES trigger (id, agent_id, company_id);
ALTER TABLE conversation ADD CONSTRAINT conversation_author_kind_ck CHECK (author_kind = 'MEMBER');
ALTER TABLE conversation ADD CONSTRAINT conversation_author_fk FOREIGN KEY (author_principal_id, company_id, author_kind)
    REFERENCES principal (id, company_id, kind);
ALTER TABLE conversation ADD CONSTRAINT conversation_chain_depth_ck CHECK (chain_depth BETWEEN 0 AND 3);

-- T4: a conversation belongs to a person or to a trigger, never both. A personal one is paid by its person (I10); a
-- trigger's has its author, the TRIGGER origin and a trigger's place.
ALTER TABLE conversation DROP CONSTRAINT conversation_payer_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_payer_ck CHECK (
    (trigger_id IS NULL AND author_principal_id IS NULL AND person_principal_id IS NOT NULL
     AND payer_principal_id = person_principal_id)
    OR (trigger_id IS NOT NULL AND person_principal_id IS NULL AND author_principal_id IS NOT NULL
        AND origin = 'TRIGGER' AND place IN ('TRACKER_ISSUE', 'SCHEDULE')));
ALTER TABLE conversation ADD CONSTRAINT conversation_place_ref_trigger_ck CHECK (
    (place <> 'TRACKER_ISSUE' OR (place_ref ? 'subjectId' AND place_ref ? 'systemId' AND place_ref ? 'key'))
    AND (place <> 'SCHEDULE' OR place_ref ? 'triggerId'));

ALTER TABLE conversation DROP CONSTRAINT conversation_ended_reason_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_ended_reason_ck CHECK (ended_reason IS NULL OR ended_reason IN (
    'USER', 'AGENT_DELETED', 'PERSON_LEFT', 'AUTHOR_LEFT', 'TRIGGER_DELETED', 'SUBJECT_CLOSED', 'SUPERSEDED'));

-- T11: at most one open conversation per trigger and subject.
CREATE UNIQUE INDEX conversation_trigger_subject_open_uq ON conversation (trigger_id, (place_ref ->> 'subjectId'))
    WHERE state = 'OPEN' AND place = 'TRACKER_ISSUE';
-- A trigger's conversations (its audience's lists, its pause, deletion, reassignment); the author's.
CREATE INDEX conversation_trigger_idx ON conversation (company_id, trigger_id, updated_at DESC, id DESC)
    WHERE trigger_id IS NOT NULL;
CREATE INDEX conversation_author_idx ON conversation (author_principal_id) WHERE author_principal_id IS NOT NULL;

-- T4 at the start: a trigger's conversation's agent, author and payer are the trigger's at that moment, and the
-- trigger is live. After the foreign keys (an AFTER trigger: the RI triggers' names sort first).
CREATE FUNCTION conversation_trigger_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.trigger_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM trigger t
             WHERE t.id = NEW.trigger_id AND t.company_id = NEW.company_id AND t.deleted_at IS NULL
               AND t.agent_id = NEW.agent_id AND t.author_principal_id = NEW.author_principal_id
               AND t.payer_principal_id = NEW.payer_principal_id) THEN
        RAISE EXCEPTION 'A trigger''s conversation starts with the trigger''s agent, author and payer'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_trigger_ck';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER conversation_trigger_ck_trg AFTER INSERT ON conversation
    FOR EACH ROW EXECUTE FUNCTION conversation_trigger_ck();

-- Whether the statement runs inside conversation_rebind, for this conversation: the function is SECURITY DEFINER, so
-- current_user is the schema owner there, which the runtime role never is, and it names the conversation it rebinds in
-- a transaction-local setting. Nothing else passes.
CREATE FUNCTION conversation_rebinding(p_conversation_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT current_user <> session_user
       AND current_setting('tinfirm.conversation_rebind', true) = p_conversation_id::text
$$;

-- What a conversation fixes for its life (I10, I12, T4): its agent, person, key, runtime, trigger, place, subject id
-- and chain depth; its author, payer and model access but by conversation_rebind (§11). The subscription's host may only
-- go; an ended conversation stays ended.
CREATE OR REPLACE FUNCTION conversation_fixed_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.agent_id IS DISTINCT FROM OLD.agent_id
       OR NEW.person_principal_id IS DISTINCT FROM OLD.person_principal_id
       OR NEW.key IS DISTINCT FROM OLD.key
       OR NEW.runtime IS DISTINCT FROM OLD.runtime
       OR NEW.trigger_id IS DISTINCT FROM OLD.trigger_id
       OR NEW.place IS DISTINCT FROM OLD.place
       OR (NEW.place_ref ->> 'subjectId') IS DISTINCT FROM (OLD.place_ref ->> 'subjectId')
       OR NEW.origin IS DISTINCT FROM OLD.origin
       OR NEW.chain_depth IS DISTINCT FROM OLD.chain_depth
       OR NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'A conversation''s agent, person, trigger, key, runtime and place are fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_fixed_ck';
    END IF;
    IF (NEW.author_principal_id IS DISTINCT FROM OLD.author_principal_id
        OR NEW.payer_principal_id IS DISTINCT FROM OLD.payer_principal_id
        OR NEW.model_access IS DISTINCT FROM OLD.model_access
        OR NEW.subscription_label IS DISTINCT FROM OLD.subscription_label
        OR (NEW.subscription_host_id IS DISTINCT FROM OLD.subscription_host_id
            AND NEW.subscription_host_id IS NOT NULL))
       AND NOT (OLD.trigger_id IS NOT NULL AND conversation_rebinding(OLD.id)) THEN
        RAISE EXCEPTION 'A conversation''s author, payer and model access are fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_fixed_ck';
    END IF;
    IF OLD.state = 'ENDED' AND NEW.state <> 'ENDED' THEN
        RAISE EXCEPTION 'An ended conversation stays ended'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_ended_final_ck';
    END IF;
    RETURN NEW;
END
$$;

-- ---- conversation_binding (contract §3.5) --------------------------------------------------------------------------

-- how gains FIXED: a trigger's conversation has only FIXED bindings, inserted BOUND when it starts, with the table's
-- account, holder and identity and no grant (T5). reason gains NOT_ENTITLED (waiting: the author lost the right to the
-- service identity) and ACCOUNT_REMOVED (gone: the reassigned trigger's new table has no row for the system, §11).
ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_how_ck;
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_how_ck CHECK (
    (how IS NULL) = (account_id IS NULL)
    AND (how IS NULL OR how IN ('OWN', 'GRANTED', 'CHOSEN', 'FIXED'))
    AND (how IS NULL OR (how IN ('OWN', 'FIXED')) = (grant_id IS NULL)));
ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_reason_ck;
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_reason_ck CHECK (
    (state = 'BOUND' AND reason IS NULL AND account_id IS NOT NULL)
    OR (state = 'WAITING' AND reason IN ('NONE', 'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'CHOOSE', 'PICK_GRANT',
                                         'SUSPENDED', 'SYSTEM_OFF', 'NOT_ENTITLED'))
    OR (state = 'GONE' AND reason IN ('GRANT_REMOVED', 'LEFT_GROUP', 'IDENTITY_CHANGED', 'HOLDER_LEFT',
                                      'SYSTEM_REMOVED', 'ACCOUNT_REMOVED')));

-- I6, I7, I8 and T5, which no foreign key can say:
--   the system is the company's, and a binding never moves to another conversation or system;
--   GONE is final (I7), but for conversation_rebind;
--   once bound, the holder, the identity, the grant and how it bound never change, and the account is replaced only by
--   the holder's account on the same system with the same identity (I6), but for conversation_rebind;
--   T5: a trigger's conversation binds only FIXED, a personal one never;
--   the bound account is the holder's on the system, and the holder is the personal conversation's person, or a
--   service identity whose account the bound grant lends (I8); for a trigger's conversation its author, or a service
--   identity, without a grant (T1).
CREATE OR REPLACE FUNCTION conversation_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person  uuid;
    v_author  uuid;
    v_trigger uuid;
    v_rebind  boolean;
BEGIN
    SELECT c.person_principal_id, c.author_principal_id, c.trigger_id INTO v_person, v_author, v_trigger
      FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
    v_rebind := v_trigger IS NOT NULL AND conversation_rebinding(NEW.conversation_id);
    IF (v_trigger IS NOT NULL AND NEW.how IS DISTINCT FROM 'FIXED')
       OR (v_trigger IS NULL AND NEW.how = 'FIXED') THEN
        RAISE EXCEPTION 'A trigger''s conversation binds the trigger''s accounts, FIXED; a personal one never does'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_fixed_how_ck';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM external_system s WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id) THEN
            RAISE EXCEPTION 'A binding is on a system of the conversation''s company'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_system_ck';
        END IF;
    ELSE
        IF NEW.conversation_id IS DISTINCT FROM OLD.conversation_id OR NEW.system_id IS DISTINCT FROM OLD.system_id
           OR NEW.company_id IS DISTINCT FROM OLD.company_id THEN
            RAISE EXCEPTION 'A binding stays with its conversation and system'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_fixed_ck';
        END IF;
        IF OLD.state = 'GONE' AND NEW.state <> 'GONE' AND NOT v_rebind THEN
            RAISE EXCEPTION 'A gone binding stays gone'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_gone_ck';
        END IF;
        IF OLD.account_id IS NOT NULL AND NOT v_rebind THEN
            IF NEW.holder_principal_id IS DISTINCT FROM OLD.holder_principal_id
               OR NEW.external_id IS DISTINCT FROM OLD.external_id
               OR NEW.grant_id IS DISTINCT FROM OLD.grant_id
               OR NEW.how IS DISTINCT FROM OLD.how
               OR NEW.account_id IS NULL THEN
                RAISE EXCEPTION 'A conversation''s account on a system is fixed for its life'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_fixed_account_ck';
            END IF;
            IF NEW.account_id <> OLD.account_id AND NOT EXISTS (
                    SELECT 1 FROM account a
                     WHERE a.id = NEW.account_id AND a.company_id = NEW.company_id
                       AND a.principal_id = NEW.holder_principal_id AND a.system_id = NEW.system_id
                       AND a.external_id IS NOT DISTINCT FROM NEW.external_id) THEN
                RAISE EXCEPTION 'An account is replaced only by the holder''s on the same system with the same identity'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_fixed_account_ck';
            END IF;
            RETURN NEW;
        END IF;
        IF OLD.account_id IS NOT NULL AND NEW.account_id IS NOT DISTINCT FROM OLD.account_id
           AND NEW.holder_principal_id IS NOT DISTINCT FROM OLD.holder_principal_id THEN
            -- conversation_rebind left this binding's account as it was (only its state may change).
            RETURN NEW;
        END IF;
    END IF;
    IF NEW.account_id IS NOT NULL THEN
        IF NOT EXISTS (
                SELECT 1 FROM account a
                 WHERE a.id = NEW.account_id AND a.company_id = NEW.company_id
                   AND a.principal_id = NEW.holder_principal_id AND a.system_id = NEW.system_id
                   AND a.external_id IS NOT DISTINCT FROM NEW.external_id) THEN
            RAISE EXCEPTION 'A binding names the holder''s account on its system, with its identity'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
        END IF;
        IF v_trigger IS NOT NULL THEN
            IF NEW.grant_id IS NOT NULL OR (NEW.holder_principal_id <> v_author AND NOT EXISTS (
                    SELECT 1 FROM principal p
                     WHERE p.id = NEW.holder_principal_id AND p.company_id = NEW.company_id AND p.kind = 'SERVICE')) THEN
                RAISE EXCEPTION 'A trigger''s conversation binds its author''s own account or a service identity''s'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
            END IF;
        ELSIF NEW.holder_principal_id = v_person THEN
            IF NEW.grant_id IS NOT NULL THEN
                RAISE EXCEPTION 'The person''s own account is bound without a grant'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
            END IF;
        ELSIF NEW.grant_id IS NULL OR NOT EXISTS (
                SELECT 1 FROM account_grant g
                 WHERE g.id = NEW.grant_id AND g.company_id = NEW.company_id AND g.account_id = NEW.account_id) THEN
            -- I8: another principal's account only through a grant that lends it, which only a service identity's
            -- account can be (account_grant_service_fk).
            RAISE EXCEPTION 'A conversation binds its person''s own account or a grant''s, never another member''s'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---- message (contract §3.6) ---------------------------------------------------------------------------------------

--   kind              gains EXTERNAL: what someone wrote in the place, a comment on the issue
--   external_id       the comment's id at the system: unique per conversation
--   external_author   {id, login, name} at the system; author_principal_id is set when a member's account on that
--                     system has that identity, else NULL
ALTER TABLE message ADD COLUMN external_id varchar(255);
ALTER TABLE message ADD COLUMN external_author jsonb;
ALTER TABLE message DROP CONSTRAINT message_kind_ck;
ALTER TABLE message ADD CONSTRAINT message_kind_ck CHECK (kind IN ('PERSON', 'AGENT', 'SYSTEM', 'EXTERNAL'));
ALTER TABLE message DROP CONSTRAINT message_author_ck;
ALTER TABLE message ADD CONSTRAINT message_author_ck CHECK (
    (kind = 'PERSON' AND author_principal_id IS NOT NULL AND external_id IS NULL AND external_author IS NULL)
    OR (kind = 'EXTERNAL' AND external_id IS NOT NULL AND external_author IS NOT NULL
        AND jsonb_typeof(external_author) = 'object')
    OR (kind IN ('AGENT', 'SYSTEM') AND author_principal_id IS NULL AND external_id IS NULL
        AND external_author IS NULL));
ALTER TABLE message DROP CONSTRAINT message_answers_ck;
ALTER TABLE message ADD CONSTRAINT message_answers_ck CHECK (kind IN ('PERSON', 'EXTERNAL') OR NOT answers_open_questions);
ALTER TABLE message ADD CONSTRAINT message_external_uq UNIQUE (conversation_id, external_id);

-- ---- run (contract §3.7) -------------------------------------------------------------------------------------------

--   trigger_id   copied from the conversation
--   turn_reason  gains EVENT (a firing of an open conversation) and REVIEW_RETURNED (a result's review denied)
--   queue_reason gains TRIGGER_PAUSED: a paused trigger's turn waits
ALTER TABLE run ADD COLUMN trigger_id uuid;
ALTER TABLE run ADD CONSTRAINT run_trigger_fk FOREIGN KEY (trigger_id, company_id) REFERENCES trigger (id, company_id);
CREATE INDEX run_trigger_idx ON run (trigger_id) WHERE trigger_id IS NOT NULL;

ALTER TABLE run DROP CONSTRAINT run_turn_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_turn_reason_ck CHECK (turn_reason IN (
    'FIRST', 'MESSAGE', 'DECIDED', 'CONNECTED', 'RETRY', 'EVENT', 'REVIEW_RETURNED'));

-- T6, the relaxed I10: a personal run is paid by its person; a trigger's run by its conversation's payer
-- (run_conversation_ck below).
ALTER TABLE run DROP CONSTRAINT run_payer_ck;
ALTER TABLE run ADD CONSTRAINT run_payer_ck CHECK (trigger_id IS NOT NULL OR payer_principal_id = acting_principal_id);

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'HOST_OFFLINE', 'MODEL_ACCESS_UNAVAILABLE',
    'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING',
    'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED',
    'TRIGGER_PAUSED', 'OTHER'));

-- T6: a run's trigger, acting principal (the person, or the trigger's conversation's author) and payer are its
-- conversation's; its model access is the turn's, which the conversation module chooses. After the foreign keys and
-- the checks (an AFTER trigger: the RI triggers' names sort first, and a CHECK such as run_payer_ck answers before it).
CREATE FUNCTION run_conversation_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_conversation record;
BEGIN
    SELECT c.trigger_id, coalesce(c.person_principal_id, c.author_principal_id) AS acting, c.payer_principal_id
      INTO v_conversation
      FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF NEW.trigger_id IS DISTINCT FROM v_conversation.trigger_id
       OR NEW.acting_principal_id IS DISTINCT FROM v_conversation.acting
       OR NEW.payer_principal_id IS DISTINCT FROM v_conversation.payer_principal_id THEN
        RAISE EXCEPTION 'A run acts and is paid as its conversation is'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'run_conversation_ck';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER run_conversation_ck_trg AFTER INSERT ON run
    FOR EACH ROW EXECUTE FUNCTION run_conversation_ck();

-- ---- decision (contract §3.8) --------------------------------------------------------------------------------------

--   approver_principal_id   nullable: a personal conversation's decisions name its person; an approval of a trigger
--                           without a team names its author
--   approver_group_id       an approval of a trigger with a team: the team. A question of a trigger's conversation names
--                           neither (anyone in the place answers). The group's removal moves its open approvals to the
--                           author first (the service); the foreign key's SET NULL keeps the history of the others
--   subject                 gains {type: RESULT, runId, summary, writes}
--   decided_by_name         an answer from the place: the commenter's name at the system
--   decided_by_message_id   the EXTERNAL or PERSON message that answered
ALTER TABLE decision ALTER COLUMN approver_principal_id DROP NOT NULL;
ALTER TABLE decision ADD COLUMN approver_group_id uuid;
ALTER TABLE decision ADD COLUMN decided_by_name varchar(255);
ALTER TABLE decision ADD COLUMN decided_by_message_id uuid;
ALTER TABLE decision ADD CONSTRAINT decision_approver_group_fk FOREIGN KEY (approver_group_id, company_id)
    REFERENCES user_group (id, company_id) ON DELETE SET NULL (approver_group_id);
ALTER TABLE decision ADD CONSTRAINT decision_decided_by_message_fk FOREIGN KEY (decided_by_message_id, company_id)
    REFERENCES message (id, company_id);
-- T12: an approval names at most one approver, and one while it is open; a question no group.
ALTER TABLE decision ADD CONSTRAINT decision_approver_ck CHECK (
    NOT (approver_principal_id IS NOT NULL AND approver_group_id IS NOT NULL)
    AND (kind = 'APPROVAL' OR approver_group_id IS NULL)
    AND (kind = 'QUESTION' OR status <> 'OPEN' OR approver_principal_id IS NOT NULL OR approver_group_id IS NOT NULL));
ALTER TABLE decision DROP CONSTRAINT decision_subject_ck;
ALTER TABLE decision ADD CONSTRAINT decision_subject_ck CHECK (subject IS NULL OR (jsonb_typeof(subject) = 'object'
    AND subject ->> 'type' IN ('TOOL_CALL', 'REPOSITORY', 'HOST', 'CHANGE_REQUEST', 'RESULT')));
CREATE INDEX decision_approver_group_idx ON decision (company_id, approver_group_id, created_at, id)
    WHERE status = 'OPEN' AND approver_group_id IS NOT NULL;
CREATE INDEX decision_decided_by_message_idx ON decision (decided_by_message_id) WHERE decided_by_message_id IS NOT NULL;

-- T12 at the insert: a personal conversation's decision names its person; a trigger's question names nobody, and a
-- trigger's approval exactly one approver (its team, or its author without one).
CREATE FUNCTION decision_conversation_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person  uuid;
    v_trigger uuid;
BEGIN
    SELECT c.person_principal_id, c.trigger_id INTO v_person, v_trigger
      FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF (v_trigger IS NULL AND (NEW.approver_principal_id IS DISTINCT FROM v_person OR NEW.approver_group_id IS NOT NULL))
       OR (v_trigger IS NOT NULL AND NEW.kind = 'QUESTION'
           AND (NEW.approver_principal_id IS NOT NULL OR NEW.approver_group_id IS NOT NULL))
       OR (v_trigger IS NOT NULL AND NEW.kind = 'APPROVAL'
           AND (NEW.approver_principal_id IS NULL) = (NEW.approver_group_id IS NULL)) THEN
        RAISE EXCEPTION 'A decision names its conversation''s approver: the person; a trigger''s team or author'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'decision_conversation_approver_ck';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER decision_conversation_ck_trg AFTER INSERT ON decision
    FOR EACH ROW EXECUTE FUNCTION decision_conversation_ck();

-- ---- conversation_rebind (contract §11, open point 6) --------------------------------------------------------------

-- The one exception to T7 and to package 3's I6, I7 and I10: once an admin reassigned a trigger whose author left and
-- it is resumed, each of its open conversations goes on under the trigger's new table. In the caller's tenant context
-- (row-level security holds for the owner too), the conversation locked:
--   its author, payer and model access become the trigger's (the payer's settings' model access);
--   each binding whose account is not the new table's row for its system is replaced by that row's account (FIXED,
--   BOUND); a binding on a system the new table has no row for (or no account) is GONE, ACCOUNT_REMOVED.
-- Only for an open conversation of a live, active trigger without an active run. The fixed-column triggers let it
-- through (conversation_rebinding) and nothing else. Answers what changed, for conversation.rebound:
-- {author: {from, to}, payer: {from, to}, modelAccess: {from, to}, systems: [{systemId, fromHolder, fromAccount,
-- fromGrant, toHolder, toAccount, state}]}. The server calls it only from a reassigned trigger's resume.
CREATE FUNCTION conversation_rebind(p_conversation_id uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    -- The schema, not pg_catalog alone: the fixed-column triggers its updates fire run with this search path. The
    -- runtime role creates nothing in the schema; pg_catalog is searched first and pg_temp last.
    SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
DECLARE
    v_conversation record;
    v_trigger      record;
    v_settings     record;
    v_binding      record;
    v_row          record;
    v_systems      jsonb := '[]'::jsonb;
    v_now          timestamptz := now();
BEGIN
    SELECT c.id, c.company_id, c.trigger_id, c.state, c.active_run_id, c.author_principal_id, c.payer_principal_id,
           c.model_access, c.subscription_label
      INTO v_conversation
      FROM ${flyway:defaultSchema}.conversation c
     WHERE c.id = p_conversation_id
       FOR NO KEY UPDATE;
    IF NOT FOUND OR v_conversation.trigger_id IS NULL OR v_conversation.state <> 'OPEN'
       OR v_conversation.active_run_id IS NOT NULL THEN
        RAISE EXCEPTION 'conversation_rebind takes an open conversation of a trigger without an active run'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;
    SELECT t.id, t.author_principal_id, t.payer_principal_id, t.status, t.deleted_at
      INTO v_trigger
      FROM ${flyway:defaultSchema}.trigger t
     WHERE t.id = v_conversation.trigger_id AND t.company_id = v_conversation.company_id;
    IF NOT FOUND OR v_trigger.deleted_at IS NOT NULL OR v_trigger.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'conversation_rebind takes the conversation of a live trigger that was resumed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;
    SELECT s.model_access, s.subscription_host_id, s.subscription_label
      INTO v_settings
      FROM ${flyway:defaultSchema}.agent_settings s
     WHERE s.trigger_id = v_trigger.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'conversation_rebind needs the trigger''s settings'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;

    PERFORM set_config('tinfirm.conversation_rebind', p_conversation_id::text, true);

    UPDATE ${flyway:defaultSchema}.conversation
       SET author_principal_id = v_trigger.author_principal_id,
           payer_principal_id = v_trigger.payer_principal_id,
           model_access = v_settings.model_access,
           subscription_host_id = CASE WHEN v_settings.model_access = 'SUBSCRIPTION'
                                       THEN v_settings.subscription_host_id END,
           subscription_label = CASE WHEN v_settings.model_access = 'SUBSCRIPTION'
                                     THEN v_settings.subscription_label END,
           updated_at = v_now
     WHERE id = p_conversation_id;

    FOR v_binding IN
        SELECT b.id, b.system_id, b.state, b.account_id, b.holder_principal_id, b.grant_id
          FROM ${flyway:defaultSchema}.conversation_binding b
         WHERE b.conversation_id = p_conversation_id
         ORDER BY b.system_id
           FOR NO KEY UPDATE
    LOOP
        SELECT a.account_id, a.holder_principal_id, acc.external_id
          INTO v_row
          FROM ${flyway:defaultSchema}.trigger_account a
          JOIN ${flyway:defaultSchema}.account acc ON acc.id = a.account_id
         WHERE a.trigger_id = v_trigger.id AND a.system_id = v_binding.system_id;
        IF FOUND THEN
            IF v_binding.account_id IS DISTINCT FROM v_row.account_id OR v_binding.state <> 'BOUND' THEN
                UPDATE ${flyway:defaultSchema}.conversation_binding
                   SET state = 'BOUND', how = 'FIXED', account_id = v_row.account_id,
                       holder_principal_id = v_row.holder_principal_id, external_id = v_row.external_id,
                       grant_id = NULL, reason = NULL, choices = '{}', waiting_since = NULL, bound_at = v_now,
                       bound_by = NULL, updated_at = v_now
                 WHERE id = v_binding.id;
                v_systems := v_systems || jsonb_build_object(
                    'systemId', v_binding.system_id, 'fromHolder', v_binding.holder_principal_id,
                    'fromAccount', v_binding.account_id, 'fromGrant', v_binding.grant_id,
                    'toHolder', v_row.holder_principal_id, 'toAccount', v_row.account_id, 'state', 'BOUND');
            END IF;
        ELSIF v_binding.state <> 'GONE' THEN
            UPDATE ${flyway:defaultSchema}.conversation_binding
               SET state = 'GONE', reason = 'ACCOUNT_REMOVED', choices = '{}', waiting_since = NULL,
                   updated_at = v_now
             WHERE id = v_binding.id;
            v_systems := v_systems || jsonb_build_object(
                'systemId', v_binding.system_id, 'fromHolder', v_binding.holder_principal_id,
                'fromAccount', v_binding.account_id, 'fromGrant', v_binding.grant_id,
                'toHolder', NULL, 'toAccount', NULL, 'state', 'GONE');
        END IF;
    END LOOP;

    PERFORM set_config('tinfirm.conversation_rebind', '', true);

    RETURN jsonb_build_object(
        'author', jsonb_build_object('from', v_conversation.author_principal_id, 'to', v_trigger.author_principal_id),
        'payer', jsonb_build_object('from', v_conversation.payer_principal_id, 'to', v_trigger.payer_principal_id),
        'modelAccess', jsonb_build_object('from', v_conversation.model_access, 'to', v_settings.model_access),
        'systems', v_systems);
END
$$;
REVOKE ALL ON FUNCTION conversation_rebind(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION conversation_rebind(uuid) TO ${app_role};

-- ---- row-level security and grants ---------------------------------------------------------------------------------

ALTER TABLE trigger ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_account FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_setting FORCE ROW LEVEL SECURITY;

CREATE POLICY trigger_tenant ON trigger FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY trigger_account_tenant ON trigger_account FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY trigger_setting_tenant ON trigger_setting FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A trigger is changed in place and soft-deleted, never removed: it stays for its conversations' history. Its table is
-- replaced as a whole. The company's limits are changed in place.
GRANT SELECT, INSERT, UPDATE ON trigger TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON trigger_account TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON trigger_setting TO ${app_role};
