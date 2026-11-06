-- Conversations (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md §3.8 to §3.13, §4 and §11):
-- package 3c of the platform redesign. A conversation is the one object of work: one agent, one place (a Tinfirm
-- thread in package 3), one person it acts for and who pays, its accounts and model access fixed for its life. Its
-- turns are runs (V004).
--
--   conversation              a conversation, its subject, workspace, latest turn and what it waits on (§3.8)
--   conversation_key_counter  the {base}-{n} keys, one counter per company and base (§3.8)
--   conversation_binding      the account a conversation uses on one system (§3.9, §5.3)
--   message                   what is said in the thread (§3.10)
--   decision                  questions and approvals the conversation's person decides (§3.12, §5.8)
--   output                    what a conversation produced: change requests, documents, links (§3.13)
--   attachment                files the person adds and the documents of outputs, scanned at upload (§3.13)
--
-- It also adds the foreign keys V004 left for this package: run.conversation_id to conversation and
-- run_repository.binding_id to conversation_binding.
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), a composite UNIQUE (id, company_id) that composite foreign keys point at, and grants to the
-- runtime role ${app_role}, which owns nothing. The schema owner runs this file. Nothing here is deleted by the
-- runtime role but attachments: a conversation, its bindings, messages, decisions and outputs are its history.

-- ---- conversation (contract §3.8) --------------------------------------------------------------------------------

--   key                    {base}-{n}, unique in the company and never reused (I12): base is the subject's key as a
--                          slug and the agent's handle (shop-123-dev), or the handle alone, cut to 80 characters; n
--                          from conversation_key_counter. At most 93 characters, so the branch agent/{key} fits 100
--   title                  the first line of the first message, at most 200 characters; the person may rename it
--   place, place_ref       TINFIRM_THREAD in package 3, without a reference. Reserved: CHAT_THREAD (package 5),
--                          TRACKER_ISSUE and SCHEDULE (package 4), whose place_ref names the thread or the issue
--   origin                 APP in package 3. Reserved: CLIENT (package 6), DIRECT_MESSAGE (5), TRIGGER (4)
--   person_principal_id    the member it acts for, is paid by and is seen by (person_kind is always MEMBER: the
--                          foreign key with it admits no service identity). Package 4 adds trigger_id and a check
--                          that exactly one is set
--   subject_*              the subject, a reference and not a copy: a company system, the key people know (SHOP-123,
--                          owner/repo#57), its link, the title given at the start; all NULL without a subject. The
--                          system's removal keeps the key, the link and the title
--   runtime                fixed at the start: the settings' runtime then (open point 3)
--   payer_principal_id,    who pays, fixed (I10): the person, BUDGET or a SUBSCRIPTION on the person's personal runner
--   model_access,          host, named by the host and the label it reports (package 2's ModelAccessChoice), as their
--   subscription_host_id,  settings said at the start. The host's removal leaves the label without a host: no turn is
--   subscription_label     paid until the person starts a new conversation (SUBSCRIPTION_GONE)
--   workspace              [{repositoryId, readOnly, source}], source AGENT or REQUESTED; at most 20 (§5.6)
--   granted_hosts          hosts approved requests gave this conversation, normalized; at most 100
--   state, ended_*         OPEN or ENDED (final); ended_reason USER (the person or OWNER ended it), AGENT_DELETED,
--                          PERSON_LEFT
--   turn_status            the latest turn: QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED; NULL before one
--   waiting_on,            PERSON (QUESTION, APPROVAL, ACCOUNT, FAILED), AGENT (RUNNING), RUNNER (the run's queue
--   waiting_reason,        reason, or QUEUED before the dispatcher looked), NONE; since when
--   waiting_since
--   result_*, failure_*    the latest turn's result (COMPLETED or BLOCKED) or its failure
CREATE TABLE conversation (
    id                    uuid           PRIMARY KEY,
    company_id            uuid           NOT NULL REFERENCES company (id),
    agent_id              uuid           NOT NULL,
    key                   varchar(93)    NOT NULL,
    title                 varchar(200)   NOT NULL,
    place                 varchar(16)    NOT NULL DEFAULT 'TINFIRM_THREAD',
    place_ref             jsonb,
    origin                varchar(16)    NOT NULL DEFAULT 'APP',
    person_principal_id   uuid           NOT NULL,
    person_kind           varchar(8)     NOT NULL DEFAULT 'MEMBER',
    subject_system_id     uuid,
    subject_key           varchar(200),
    subject_url           varchar(2000),
    subject_title         varchar(500),
    runtime               varchar(16)    NOT NULL,
    payer_principal_id    uuid           NOT NULL,
    model_access          varchar(16)    NOT NULL,
    subscription_host_id  uuid,
    subscription_label    varchar(63),
    workspace             jsonb          NOT NULL DEFAULT '[]'::jsonb,
    granted_hosts         varchar(260)[] NOT NULL DEFAULT '{}',
    state                 varchar(8)     NOT NULL DEFAULT 'OPEN',
    ended_reason          varchar(16),
    ended_at              timestamptz,
    ended_by              uuid           REFERENCES app_user (id) ON DELETE SET NULL,
    turn_status           varchar(16),
    waiting_on            varchar(8)     NOT NULL DEFAULT 'NONE',
    waiting_reason        varchar(32),
    waiting_since         timestamptz,
    turn_count            integer        NOT NULL DEFAULT 0,
    active_run_id         uuid,
    last_run_id           uuid,
    last_turn_at          timestamptz,
    result_outcome        varchar(16),
    result_summary        text,
    result_next_steps     jsonb,
    result_open_questions jsonb,
    failure_class         varchar(32),
    failure_cause         varchar(2000),
    created_at            timestamptz    NOT NULL,
    updated_at            timestamptz    NOT NULL,
    created_by            uuid           REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT conversation_id_company_uq UNIQUE (id, company_id),
    -- I12: each conversation has its own key, never reused.
    CONSTRAINT conversation_key_uq UNIQUE (company_id, key),
    CONSTRAINT conversation_agent_fk FOREIGN KEY (agent_id, company_id) REFERENCES agent (id, company_id),
    CONSTRAINT conversation_person_kind_ck CHECK (person_kind = 'MEMBER'),
    CONSTRAINT conversation_person_fk FOREIGN KEY (person_principal_id, company_id, person_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT conversation_payer_fk FOREIGN KEY (payer_principal_id, company_id)
        REFERENCES principal (id, company_id),
    CONSTRAINT conversation_subject_system_fk FOREIGN KEY (subject_system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE SET NULL (subject_system_id),
    CONSTRAINT conversation_subscription_host_fk FOREIGN KEY (subscription_host_id, company_id)
        REFERENCES runner_host (id, company_id) ON DELETE SET NULL (subscription_host_id),
    CONSTRAINT conversation_key_ck CHECK (length(key) BETWEEN 1 AND 93 AND key ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    CONSTRAINT conversation_title_ck CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT conversation_place_ck CHECK (place IN ('TINFIRM_THREAD', 'CHAT_THREAD', 'TRACKER_ISSUE', 'SCHEDULE')),
    CONSTRAINT conversation_place_ref_ck CHECK (
        (place = 'TINFIRM_THREAD' AND place_ref IS NULL)
        OR (place <> 'TINFIRM_THREAD' AND jsonb_typeof(place_ref) = 'object')),
    CONSTRAINT conversation_origin_ck CHECK (origin IN ('APP', 'CLIENT', 'DIRECT_MESSAGE', 'TRIGGER')),
    -- A subject has its key; its system, link and title only with it.
    CONSTRAINT conversation_subject_ck CHECK (
        subject_key IS NOT NULL OR (subject_system_id IS NULL AND subject_url IS NULL AND subject_title IS NULL)),
    CONSTRAINT conversation_subject_key_ck CHECK (subject_key IS NULL OR length(btrim(subject_key)) BETWEEN 1 AND 200),
    -- An http(s) link without user info.
    CONSTRAINT conversation_subject_url_ck CHECK (
        subject_url IS NULL OR subject_url ~ '^https?://[^/?#@[:space:]]+([/?#][^[:space:]]*)?$'),
    CONSTRAINT conversation_runtime_ck CHECK (runtime IN ('CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE')),
    -- I10: a personal conversation's payer is its person; package 4 relaxes this for a trigger's conversations.
    CONSTRAINT conversation_payer_ck CHECK (payer_principal_id = person_principal_id),
    CONSTRAINT conversation_model_access_ck CHECK (
        (model_access = 'BUDGET' AND subscription_host_id IS NULL AND subscription_label IS NULL)
        OR (model_access = 'SUBSCRIPTION' AND subscription_label IS NOT NULL)),
    CONSTRAINT conversation_workspace_ck CHECK (
        jsonb_typeof(workspace) = 'array' AND jsonb_array_length(workspace) <= 20),
    CONSTRAINT conversation_granted_hosts_ck CHECK (cardinality(granted_hosts) <= 100),
    CONSTRAINT conversation_state_ck CHECK (state IN ('OPEN', 'ENDED')),
    CONSTRAINT conversation_ended_reason_ck CHECK (
        ended_reason IS NULL OR ended_reason IN ('USER', 'AGENT_DELETED', 'PERSON_LEFT')),
    CONSTRAINT conversation_ended_ck CHECK (
        (state = 'ENDED') = (ended_reason IS NOT NULL AND ended_at IS NOT NULL)
        AND (ended_by IS NULL OR state = 'ENDED')),
    CONSTRAINT conversation_turn_status_ck CHECK (turn_status IS NULL OR turn_status IN (
        'QUEUED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT conversation_waiting_on_ck CHECK (waiting_on IN ('PERSON', 'AGENT', 'RUNNER', 'NONE')),
    CONSTRAINT conversation_waiting_ck CHECK (
        (waiting_on = 'NONE') = (waiting_reason IS NULL)
        AND (waiting_on = 'NONE') = (waiting_since IS NULL)),
    CONSTRAINT conversation_turn_count_ck CHECK (turn_count >= 0),
    CONSTRAINT conversation_result_outcome_ck CHECK (
        result_outcome IS NULL OR result_outcome IN ('COMPLETED', 'BLOCKED')),
    CONSTRAINT conversation_result_ck CHECK (
        (result_next_steps IS NULL OR jsonb_typeof(result_next_steps) = 'array')
        AND (result_open_questions IS NULL OR jsonb_typeof(result_open_questions) = 'array')),
    CONSTRAINT conversation_failure_ck CHECK (failure_class IS NOT NULL OR failure_cause IS NULL)
);

-- GET /conversations: a person's, and OWNER's view of all, newest activity first (keyset on updated_at, id).
CREATE INDEX conversation_person_idx ON conversation (company_id, person_principal_id, updated_at DESC, id DESC);
CREATE INDEX conversation_company_idx ON conversation (company_id, updated_at DESC, id DESC);
-- GET /subjects and the subject filter.
CREATE INDEX conversation_subject_idx ON conversation (company_id, subject_system_id, subject_key)
    WHERE subject_key IS NOT NULL;
-- An agent's conversations (its deletion ends them); the foreign keys' lookups.
CREATE INDEX conversation_agent_idx ON conversation (agent_id);
CREATE INDEX conversation_subject_system_idx ON conversation (subject_system_id) WHERE subject_system_id IS NOT NULL;
CREATE INDEX conversation_subscription_host_idx ON conversation (subscription_host_id)
    WHERE subscription_host_id IS NOT NULL;
CREATE INDEX conversation_payer_idx ON conversation (payer_principal_id);

-- What a conversation fixes for its life (I10, I12): its agent, person, key, runtime, payer and model access. The
-- subscription's host may only go (its removal, ON DELETE SET NULL); an ended conversation stays ended.
CREATE FUNCTION conversation_fixed_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.agent_id IS DISTINCT FROM OLD.agent_id
       OR NEW.person_principal_id IS DISTINCT FROM OLD.person_principal_id
       OR NEW.key IS DISTINCT FROM OLD.key
       OR NEW.runtime IS DISTINCT FROM OLD.runtime
       OR NEW.payer_principal_id IS DISTINCT FROM OLD.payer_principal_id
       OR NEW.model_access IS DISTINCT FROM OLD.model_access
       OR NEW.subscription_label IS DISTINCT FROM OLD.subscription_label
       OR (NEW.subscription_host_id IS DISTINCT FROM OLD.subscription_host_id AND NEW.subscription_host_id IS NOT NULL)
       OR NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'A conversation''s agent, person, key, runtime, payer and model access are fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_fixed_ck';
    END IF;
    IF OLD.state = 'ENDED' AND NEW.state <> 'ENDED' THEN
        RAISE EXCEPTION 'An ended conversation stays ended'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_ended_final_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER conversation_fixed_ck_trg BEFORE UPDATE ON conversation
    FOR EACH ROW EXECUTE FUNCTION conversation_fixed_ck();

-- The n of {base}-{n}, advanced under the row's lock: keys are never reused.
CREATE TABLE conversation_key_counter (
    company_id uuid        NOT NULL REFERENCES company (id),
    base       varchar(80) NOT NULL,
    next       bigint      NOT NULL DEFAULT 1,
    CONSTRAINT conversation_key_counter_pk PRIMARY KEY (company_id, base),
    CONSTRAINT conversation_key_counter_next_ck CHECK (next >= 1)
);

-- ---- conversation_binding (contract §3.9, §5.3) ------------------------------------------------------------------

-- The account a conversation uses on one system, resolved at the system's first use through Accounts.bind and
-- checked at every use (ConversationBindings.use). One per system per conversation, fixed for its life (I6).
--
--   system_id            the system. No foreign key: a removed system makes the binding GONE (SYSTEM_REMOVED) and
--                        the binding stays as the conversation's history; the trigger checks it at insert
--   state                BOUND; WAITING (a card: the person or an admin acts first); GONE (final: the system can
--                        never be used again in this conversation)
--   how                  OWN (the person's own account), GRANTED (the one usable grant), CHOSEN (the person picked a
--                        grant); NULL until first bound
--   account_id,          the bound account, its holder and its identity on the system (NULL where it has none):
--   holder_principal_id, NULL until first bound, then set once (I6). The account may be replaced only by the holder's
--   external_id          account on the same system with the same identity (a reconnect). No foreign keys: an
--                        account, a grant and a service identity may go, and the binding keeps naming them
--   grant_id             the grant it acts through; NULL for the person's own account
--   reason               why it waits (NONE, RECONNECT_YOURS, RECONNECT_COMPANY, CHOOSE, PICK_GRANT, SUSPENDED,
--                        SYSTEM_OFF) or why it is gone (GRANT_REMOVED, LEFT_GROUP, IDENTITY_CHANGED, HOLDER_LEFT,
--                        SYSTEM_REMOVED); NULL while BOUND
--   choices              the grant ids offered for PICK_GRANT and CHOOSE
--   waiting_calls        tool calls parked while it waits, [{runId, requestId, tool, arguments, result}], at most 10
--   waiting_since        since when it waits, for the inbox and to audit each wait once
--   bound_by             the person, when they chose
CREATE TABLE conversation_binding (
    id                  uuid         PRIMARY KEY,
    company_id          uuid         NOT NULL,
    conversation_id     uuid         NOT NULL,
    system_id           uuid         NOT NULL,
    state               varchar(8)   NOT NULL,
    how                 varchar(8),
    account_id          uuid,
    holder_principal_id uuid,
    external_id         varchar(255),
    grant_id            uuid,
    reason              varchar(24),
    choices             uuid[]       NOT NULL DEFAULT '{}',
    waiting_calls       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    waiting_since       timestamptz,
    bound_at            timestamptz,
    bound_by            uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT conversation_binding_id_company_uq UNIQUE (id, company_id),
    -- I6: one account per system per conversation.
    CONSTRAINT conversation_binding_system_uq UNIQUE (conversation_id, system_id),
    CONSTRAINT conversation_binding_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT conversation_binding_state_ck CHECK (state IN ('BOUND', 'WAITING', 'GONE')),
    CONSTRAINT conversation_binding_how_ck CHECK (
        (how IS NULL) = (account_id IS NULL)
        AND (how IS NULL OR how IN ('OWN', 'GRANTED', 'CHOSEN'))
        AND (how IS NULL OR (how = 'OWN') = (grant_id IS NULL))),
    CONSTRAINT conversation_binding_account_ck CHECK (
        (account_id IS NULL AND holder_principal_id IS NULL AND external_id IS NULL AND grant_id IS NULL
         AND bound_at IS NULL)
        OR (account_id IS NOT NULL AND holder_principal_id IS NOT NULL AND bound_at IS NOT NULL)),
    CONSTRAINT conversation_binding_reason_ck CHECK (
        (state = 'BOUND' AND reason IS NULL AND account_id IS NOT NULL)
        OR (state = 'WAITING' AND reason IN ('NONE', 'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'CHOOSE', 'PICK_GRANT',
                                             'SUSPENDED', 'SYSTEM_OFF'))
        OR (state = 'GONE' AND reason IN ('GRANT_REMOVED', 'LEFT_GROUP', 'IDENTITY_CHANGED', 'HOLDER_LEFT',
                                          'SYSTEM_REMOVED'))),
    CONSTRAINT conversation_binding_choices_ck CHECK (
        cardinality(choices) <= 100 AND (cardinality(choices) = 0 OR reason IN ('PICK_GRANT', 'CHOOSE'))),
    CONSTRAINT conversation_binding_waiting_calls_ck CHECK (
        jsonb_typeof(waiting_calls) = 'array' AND jsonb_array_length(waiting_calls) <= 10),
    CONSTRAINT conversation_binding_waiting_since_ck CHECK ((state = 'WAITING') = (waiting_since IS NOT NULL))
);

-- The bindings a package 1 event concerns: by system, by account, by grant, by holder.
CREATE INDEX conversation_binding_system_idx ON conversation_binding (company_id, system_id);
CREATE INDEX conversation_binding_account_idx ON conversation_binding (account_id) WHERE account_id IS NOT NULL;
CREATE INDEX conversation_binding_grant_idx ON conversation_binding (grant_id) WHERE grant_id IS NOT NULL;
CREATE INDEX conversation_binding_holder_idx ON conversation_binding (holder_principal_id)
    WHERE holder_principal_id IS NOT NULL;
-- The inbox: waiting bindings, longest waiting first.
CREATE INDEX conversation_binding_waiting_idx ON conversation_binding (company_id, waiting_since, id)
    WHERE state = 'WAITING';

-- I6, I7 and I8, which no foreign key can say:
--   the system is the company's, and a binding never moves to another conversation or system;
--   GONE is final (I7);
--   once bound, the holder, the identity, the grant and how it bound never change, and the account is replaced only by
--   the holder's account on the same system with the same identity (I6);
--   the bound account is the holder's on the system, and the holder is the conversation's person, or a service
--   identity whose account the bound grant lends (I8): never another member's account.
CREATE FUNCTION conversation_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person uuid;
BEGIN
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
        IF OLD.state = 'GONE' AND NEW.state <> 'GONE' THEN
            RAISE EXCEPTION 'A gone binding stays gone'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_gone_ck';
        END IF;
        IF OLD.account_id IS NOT NULL THEN
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
        SELECT c.person_principal_id INTO v_person
          FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
        IF NEW.holder_principal_id = v_person THEN
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

CREATE TRIGGER conversation_binding_ck_trg BEFORE INSERT OR UPDATE ON conversation_binding
    FOR EACH ROW EXECUTE FUNCTION conversation_binding_ck();

-- ---- message (contract §3.10) ------------------------------------------------------------------------------------

-- What is said in a Tinfirm thread.
--   kind                    PERSON (author_principal_id: the person), AGENT, SYSTEM
--   answers_open_questions  a PERSON message that answers every open question (ADR-0033)
--   run_id                  the turn that took a PERSON message (NULL: it waits for the next turn), or that produced
--                           an AGENT or SYSTEM one
--   unanswered              a PERSON message that waited when its turn was cancelled or its conversation ended: it
--                           stays unanswered, and no later turn takes it (contract §5.7)
--   decision_id             a question's or an approval's card, and an answer through it
-- Packages 4 and 5 add external_id and external_author for what is said in the place itself.
CREATE TABLE message (
    id                     uuid         PRIMARY KEY,
    company_id             uuid         NOT NULL,
    conversation_id        uuid         NOT NULL,
    kind                   varchar(8)   NOT NULL,
    author_principal_id    uuid,
    body                   text         NOT NULL,
    answers_open_questions boolean      NOT NULL DEFAULT false,
    run_id                 uuid,
    unanswered             boolean      NOT NULL DEFAULT false,
    decision_id            uuid,
    created_at             timestamptz  NOT NULL,
    CONSTRAINT message_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT message_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT message_author_fk FOREIGN KEY (author_principal_id, company_id) REFERENCES principal (id, company_id),
    CONSTRAINT message_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT message_kind_ck CHECK (kind IN ('PERSON', 'AGENT', 'SYSTEM')),
    CONSTRAINT message_author_ck CHECK ((kind = 'PERSON') = (author_principal_id IS NOT NULL)),
    CONSTRAINT message_answers_ck CHECK (kind = 'PERSON' OR NOT answers_open_questions),
    CONSTRAINT message_unanswered_ck CHECK (NOT unanswered OR (kind = 'PERSON' AND run_id IS NULL)),
    CONSTRAINT message_body_ck CHECK (length(body) BETWEEN 1 AND 100000)
);

-- The thread, oldest first; the messages that wait for the next turn, oldest first.
CREATE INDEX message_conversation_idx ON message (conversation_id, created_at, id);
CREATE INDEX message_waiting_idx ON message (conversation_id, created_at, id)
    WHERE kind = 'PERSON' AND run_id IS NULL AND NOT unanswered;
CREATE INDEX message_run_idx ON message (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX message_author_idx ON message (author_principal_id) WHERE author_principal_id IS NOT NULL;

-- ---- decision (contract §3.12, §5.8) -----------------------------------------------------------------------------

-- A question or an approval the conversation's person decides.
--   run_id, request_id   the run and the platform request or tool call that raised it: a request id sent again gets
--                        the same decision (unique per conversation); NULL for what the server raises itself (a
--                        BLOCKED result's open questions, a change request under ASK)
--   question, options    a QUESTION: 1 to 2000 characters, at most 10 options of 1 to 100
--   subject, reason      an APPROVAL's subject: {type: TOOL_CALL, systemId, tool, arguments, what},
--                        {type: REPOSITORY, repositoryId, access}, {type: HOST, host}, {type: CHANGE_REQUEST,
--                        repositoryId}; package 4 adds {type: RESULT}. reason is the agent's, for a request
--   approver_principal_id who decides: the conversation's person. Package 4 adds approver_group_id (a trigger's team)
--                        and a check that an approval names exactly one
--   status               OPEN, DECIDED (once, I16), CANCELLED with its cause
--   outcome              ANSWERED (answer, option) for a question; APPROVED or DENIED (note; scope CONVERSATION or
--                        AGENT and access for an approved request) for an approval
--   result               an approved call's result, at most 50 000 characters
CREATE TABLE decision (
    id                      uuid          PRIMARY KEY,
    company_id              uuid          NOT NULL,
    conversation_id         uuid          NOT NULL,
    run_id                  uuid,
    request_id              uuid,
    kind                    varchar(8)    NOT NULL,
    question                varchar(2000),
    options                 jsonb,
    subject                 jsonb,
    reason                  varchar(2000),
    approver_principal_id   uuid          NOT NULL,
    status                  varchar(16)   NOT NULL DEFAULT 'OPEN',
    outcome                 varchar(8),
    answer                  text,
    option                  varchar(100),
    note                    text,
    scope                   varchar(16),
    access                  varchar(8),
    result                  text,
    decided_by_principal_id uuid,
    decided_at              timestamptz,
    cancel_cause            varchar(32),
    created_at              timestamptz   NOT NULL,
    CONSTRAINT decision_id_company_uq UNIQUE (id, company_id),
    -- A request id sent again (after a reconnect) gets the same decision.
    CONSTRAINT decision_request_uq UNIQUE (conversation_id, request_id),
    CONSTRAINT decision_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT decision_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT decision_approver_fk FOREIGN KEY (approver_principal_id, company_id)
        REFERENCES principal (id, company_id),
    CONSTRAINT decision_decided_by_fk FOREIGN KEY (decided_by_principal_id, company_id)
        REFERENCES principal (id, company_id),
    CONSTRAINT decision_kind_ck CHECK (kind IN ('QUESTION', 'APPROVAL')),
    CONSTRAINT decision_shape_ck CHECK (
        (kind = 'QUESTION' AND question IS NOT NULL AND subject IS NULL)
        OR (kind = 'APPROVAL' AND question IS NULL AND options IS NULL AND subject IS NOT NULL)),
    CONSTRAINT decision_question_ck CHECK (question IS NULL OR length(btrim(question)) BETWEEN 1 AND 2000),
    CONSTRAINT decision_options_ck CHECK (
        options IS NULL OR (jsonb_typeof(options) = 'array' AND jsonb_array_length(options) <= 10)),
    CONSTRAINT decision_subject_ck CHECK (subject IS NULL OR (jsonb_typeof(subject) = 'object'
        AND subject ->> 'type' IN ('TOOL_CALL', 'REPOSITORY', 'HOST', 'CHANGE_REQUEST'))),
    CONSTRAINT decision_status_ck CHECK (status IN ('OPEN', 'DECIDED', 'CANCELLED')),
    CONSTRAINT decision_decided_ck CHECK ((status = 'DECIDED') = (outcome IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT decision_outcome_ck CHECK (outcome IS NULL
        OR (kind = 'QUESTION' AND outcome = 'ANSWERED')
        OR (kind = 'APPROVAL' AND outcome IN ('APPROVED', 'DENIED'))),
    CONSTRAINT decision_scope_ck CHECK (scope IS NULL OR (outcome = 'APPROVED' AND scope IN ('CONVERSATION', 'AGENT'))),
    CONSTRAINT decision_access_ck CHECK (access IS NULL OR (outcome = 'APPROVED' AND access IN ('READ', 'WRITE'))),
    CONSTRAINT decision_cancel_ck CHECK ((status = 'CANCELLED') = (cancel_cause IS NOT NULL)),
    CONSTRAINT decision_answer_ck CHECK (answer IS NULL OR length(answer) <= 50000),
    CONSTRAINT decision_note_ck CHECK (note IS NULL OR length(note) <= 20000),
    CONSTRAINT decision_result_ck CHECK (result IS NULL OR length(result) <= 50000)
);

-- The open decisions of a conversation (at most 10 questions and 10 approvals: the service), its history; the inbox's
-- decisions of an approver, longest waiting first.
CREATE INDEX decision_conversation_idx ON decision (conversation_id, status, created_at, id);
CREATE INDEX decision_approver_idx ON decision (company_id, approver_principal_id, created_at, id)
    WHERE status = 'OPEN';
CREATE INDEX decision_run_idx ON decision (run_id) WHERE run_id IS NOT NULL;

-- I16: a decision is decided (or cancelled) once; an approved call's result is written after it.
CREATE FUNCTION decision_final_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'OPEN' AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.outcome IS DISTINCT FROM OLD.outcome) THEN
        RAISE EXCEPTION 'A decision is decided once'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'decision_final_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER decision_final_ck_trg BEFORE UPDATE ON decision
    FOR EACH ROW EXECUTE FUNCTION decision_final_ck();

ALTER TABLE message ADD CONSTRAINT message_decision_fk FOREIGN KEY (decision_id, company_id)
    REFERENCES decision (id, company_id);
CREATE INDEX message_decision_idx ON message (decision_id) WHERE decision_id IS NOT NULL;

-- ---- attachment (contract §3.13) ---------------------------------------------------------------------------------

-- Files the person adds to a conversation and the documents of its outputs (ADR-0014, ADR-0021), ADR-0014's design with
-- the conversation in place of the task. The content lives in this table (bytea, out of line), so it shares the
-- database's backups, row-level security and tenant isolation; lists never select it. The server scans every file for
-- credentials before it inserts the row, and keeps ADR-0014's limits per conversation (25 MB a file, 50 files, 250 MB).
--   file_name             shown to people and the file's name in the run's /attachments: no path separators and no
--                         control characters (the server), unique within the conversation
--   sha256                hex SHA-256 of the content; the runner host checks it after the download
--   uploaded_by_user_id   the person; uploaded_by_agent_id and run_id for a file an agent produced in a run
-- A document's file is not deleted while it is an output (output_attachment_fk).
CREATE TABLE attachment (
    id                   uuid          PRIMARY KEY,
    company_id           uuid          NOT NULL,
    conversation_id      uuid          NOT NULL,
    file_name            varchar(255)  NOT NULL,
    content_type         varchar(255)  NOT NULL,
    size_bytes           bigint        NOT NULL,
    sha256               char(64)      NOT NULL,
    content              bytea         NOT NULL,
    note                 varchar(2000),
    uploaded_by_user_id  uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    uploaded_by_agent_id uuid,
    run_id               uuid,
    created_at           timestamptz   NOT NULL,
    CONSTRAINT attachment_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT attachment_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT attachment_agent_fk FOREIGN KEY (uploaded_by_agent_id, company_id) REFERENCES agent (id, company_id),
    CONSTRAINT attachment_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT attachment_name_uq UNIQUE (conversation_id, file_name),
    CONSTRAINT attachment_name_ck CHECK (length(file_name) BETWEEN 1 AND 255
        AND file_name NOT IN ('.', '..') AND position('/' IN file_name) = 0 AND position('\' IN file_name) = 0),
    CONSTRAINT attachment_size_ck CHECK (size_bytes >= 0 AND size_bytes = octet_length(content)),
    CONSTRAINT attachment_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- An agent's file has the agent and the run and no uploading user; a person's file neither.
    CONSTRAINT attachment_uploader_ck CHECK (
        (uploaded_by_agent_id IS NULL AND run_id IS NULL)
        OR (uploaded_by_agent_id IS NOT NULL AND run_id IS NOT NULL AND uploaded_by_user_id IS NULL))
);

-- The conversation's files, oldest first; the foreign keys' lookups.
CREATE INDEX attachment_conversation_idx ON attachment (conversation_id, created_at, id);
CREATE INDEX attachment_run_idx ON attachment (run_id) WHERE run_id IS NOT NULL;

-- Content is compressed poorly by pglz and is usually already compressed: store it out of line uncompressed.
ALTER TABLE attachment ALTER COLUMN content SET STORAGE EXTERNAL;

-- ---- output (contract §3.13) -------------------------------------------------------------------------------------

-- What a conversation produced.
--   kind                  CHANGE_REQUEST (url, repository, external number, state), DOCUMENT (attachment_id), LINK
--                         (url)
--   url                   a link's or a change request's: http(s), no user info, at most 2000 characters
--   request_id            a link's (save_output, run.request ARTIFACT): a request id sent again saves nothing twice
CREATE TABLE output (
    id                   uuid          PRIMARY KEY,
    company_id           uuid          NOT NULL,
    conversation_id      uuid          NOT NULL,
    run_id               uuid,
    kind                 varchar(16)   NOT NULL,
    title                varchar(200)  NOT NULL,
    url                  varchar(2000),
    attachment_id        uuid,
    repository_id        uuid,
    external_number      varchar(50),
    change_request_state varchar(16),
    request_id           uuid,
    created_at           timestamptz   NOT NULL,
    CONSTRAINT output_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT output_request_uq UNIQUE (conversation_id, request_id),
    CONSTRAINT output_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT output_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT output_attachment_fk FOREIGN KEY (attachment_id, company_id) REFERENCES attachment (id, company_id),
    CONSTRAINT output_repository_fk FOREIGN KEY (repository_id, company_id)
        REFERENCES system_repository (id, company_id) ON DELETE SET NULL (repository_id),
    CONSTRAINT output_kind_ck CHECK (kind IN ('CHANGE_REQUEST', 'DOCUMENT', 'LINK')),
    CONSTRAINT output_title_ck CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT output_url_ck CHECK (url IS NULL OR url ~ '^https?://[^/?#@[:space:]]+([/?#][^[:space:]]*)?$'),
    CONSTRAINT output_shape_ck CHECK (
        (kind = 'CHANGE_REQUEST' AND url IS NOT NULL AND attachment_id IS NULL)
        OR (kind = 'DOCUMENT' AND attachment_id IS NOT NULL AND url IS NULL AND repository_id IS NULL)
        OR (kind = 'LINK' AND url IS NOT NULL AND attachment_id IS NULL AND repository_id IS NULL)),
    CONSTRAINT output_cr_state_ck CHECK (
        (kind = 'CHANGE_REQUEST') = (change_request_state IS NOT NULL)
        AND (change_request_state IS NULL
             OR change_request_state IN ('OPEN', 'DRAFT', 'MERGED', 'CLOSED', 'UNKNOWN')))
);

CREATE UNIQUE INDEX output_attachment_uq ON output (attachment_id) WHERE attachment_id IS NOT NULL;
CREATE INDEX output_conversation_idx ON output (conversation_id, created_at, id);
CREATE INDEX output_run_idx ON output (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX output_repository_idx ON output (repository_id) WHERE repository_id IS NOT NULL;

-- ---- the foreign keys V004 left for this package -----------------------------------------------------------------

ALTER TABLE run ADD CONSTRAINT run_conversation_fk FOREIGN KEY (conversation_id, company_id)
    REFERENCES conversation (id, company_id);

ALTER TABLE run_repository ADD CONSTRAINT run_repository_binding_fk FOREIGN KEY (binding_id, company_id)
    REFERENCES conversation_binding (id, company_id);

-- The conversation's active and last run (conversation references run, run references conversation).
ALTER TABLE conversation ADD CONSTRAINT conversation_active_run_fk FOREIGN KEY (active_run_id, company_id)
    REFERENCES run (id, company_id);
ALTER TABLE conversation ADD CONSTRAINT conversation_last_run_fk FOREIGN KEY (last_run_id, company_id)
    REFERENCES run (id, company_id);
CREATE INDEX conversation_active_run_idx ON conversation (active_run_id) WHERE active_run_id IS NOT NULL;
CREATE INDEX conversation_last_run_idx ON conversation (last_run_id) WHERE last_run_id IS NOT NULL;

-- ---- row-level security and grants -------------------------------------------------------------------------------

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_key_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_key_counter FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_binding FORCE ROW LEVEL SECURITY;
ALTER TABLE message ENABLE ROW LEVEL SECURITY;
ALTER TABLE message FORCE ROW LEVEL SECURITY;
ALTER TABLE decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE decision FORCE ROW LEVEL SECURITY;
ALTER TABLE attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE attachment FORCE ROW LEVEL SECURITY;
ALTER TABLE output ENABLE ROW LEVEL SECURITY;
ALTER TABLE output FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_tenant ON conversation FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY conversation_key_counter_tenant ON conversation_key_counter FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY conversation_binding_tenant ON conversation_binding FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY message_tenant ON message FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY decision_tenant ON decision FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY attachment_tenant ON attachment FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY output_tenant ON output FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A conversation, its bindings, messages, decisions and outputs are its history: changed in place, never deleted.
-- Attachments are deleted by the person (never while they are an output's document).
GRANT SELECT, INSERT, UPDATE ON conversation, conversation_key_counter, conversation_binding, message, decision,
    output TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON attachment TO ${app_role};
