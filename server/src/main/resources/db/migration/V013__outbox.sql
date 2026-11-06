-- The write-back outbox (the package 4 contract, docs/reviews/2026-10-12-package-4-contract.md §3.13 and §5.9): what
-- the write-back still has to write to a trigger's conversation's place, filled in the transaction that commits its
-- cause, and delivered by OutboxDelivery with the conversation's binding on the place's system (T13), in order per
-- conversation, each row at most once.
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to the runtime role ${app_role}.

--   conversation_id, system_id,  the place and the binding that writes it. No foreign key on the system: a removed
--   binding_id, subject_key      system deletes its triggers, whose rows are dropped and stay as history
--   seq                          the order within the conversation
--   kind                         COMMENT, TRANSITION, LABEL_ADD, LABEL_REMOVE, CLOSE, ATTACHMENT, REMOTE_LINK
--   cause, cause_key             RESULT, FAILURE, QUESTION, ANSWER, ON_SUCCESS, ON_FAILURE, CHANGE_REQUEST; a cause
--                                queued twice queues once (T13)
--   run_id, decision_id, output_id, attachment_id, message_id   what it carries
--   body, value, url             a comment's Markdown (at most 30 000 characters, the fixed comment tool's), a
--                                transition's or a label's name, a link
--   status                       PENDING, HELD (its trigger is paused), DELIVERED, DROPPED
--   attempts, next_attempt_at, claimed_until, ambiguous, last_error   the delivery's retries (§5.9)
--   external_id, delivered_at    what the system answered
--   dropped_reason               why it was dropped
CREATE TABLE outbox (
    id              uuid          PRIMARY KEY,
    company_id      uuid          NOT NULL,
    conversation_id uuid          NOT NULL,
    system_id       uuid          NOT NULL,
    binding_id      uuid          NOT NULL,
    subject_key     varchar(255)  NOT NULL,
    seq             integer       NOT NULL,
    kind            varchar(16)   NOT NULL,
    cause           varchar(16)   NOT NULL,
    cause_key       varchar(200)  NOT NULL,
    run_id          uuid,
    decision_id     uuid,
    output_id       uuid,
    attachment_id   uuid,
    message_id      uuid,
    body            text,
    value           varchar(255),
    url             varchar(2000),
    status          varchar(10)   NOT NULL DEFAULT 'PENDING',
    attempts        integer       NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    claimed_until   timestamptz,
    ambiguous       boolean       NOT NULL DEFAULT false,
    last_error      varchar(2000),
    external_id     varchar(255),
    delivered_at    timestamptz,
    dropped_reason  varchar(2000),
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    CONSTRAINT outbox_id_company_uq UNIQUE (id, company_id),
    -- T13: a cause queued twice queues once; the order within a conversation is one.
    CONSTRAINT outbox_cause_uq UNIQUE (conversation_id, cause_key),
    CONSTRAINT outbox_seq_uq UNIQUE (conversation_id, seq),
    CONSTRAINT outbox_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT outbox_binding_fk FOREIGN KEY (binding_id, company_id)
        REFERENCES conversation_binding (id, company_id),
    CONSTRAINT outbox_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT outbox_decision_fk FOREIGN KEY (decision_id, company_id) REFERENCES decision (id, company_id),
    CONSTRAINT outbox_output_fk FOREIGN KEY (output_id, company_id) REFERENCES output (id, company_id),
    CONSTRAINT outbox_attachment_fk FOREIGN KEY (attachment_id, company_id)
        REFERENCES attachment (id, company_id) ON DELETE SET NULL (attachment_id),
    CONSTRAINT outbox_message_fk FOREIGN KEY (message_id, company_id) REFERENCES message (id, company_id),
    CONSTRAINT outbox_seq_ck CHECK (seq >= 1),
    CONSTRAINT outbox_kind_ck CHECK (kind IN ('COMMENT', 'TRANSITION', 'LABEL_ADD', 'LABEL_REMOVE', 'CLOSE',
        'ATTACHMENT', 'REMOTE_LINK')),
    CONSTRAINT outbox_cause_ck CHECK (cause IN ('RESULT', 'FAILURE', 'QUESTION', 'ANSWER', 'ON_SUCCESS', 'ON_FAILURE',
        'CHANGE_REQUEST')),
    -- Each kind carries what it writes: a comment its body, a transition and a label its name, a remote link its url.
    CONSTRAINT outbox_shape_ck CHECK (
        (kind = 'COMMENT' AND body IS NOT NULL AND length(body) BETWEEN 1 AND 30000)
        OR (kind IN ('TRANSITION', 'LABEL_ADD', 'LABEL_REMOVE') AND value IS NOT NULL AND length(btrim(value)) >= 1)
        OR kind = 'CLOSE'
        OR kind = 'ATTACHMENT'
        OR (kind = 'REMOTE_LINK' AND url IS NOT NULL)),
    CONSTRAINT outbox_url_ck CHECK (url IS NULL OR url ~ '^https?://[^/?#@[:space:]]+([/?#][^[:space:]]*)?$'),
    CONSTRAINT outbox_status_ck CHECK (status IN ('PENDING', 'HELD', 'DELIVERED', 'DROPPED')),
    CONSTRAINT outbox_attempts_ck CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT outbox_delivered_ck CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CONSTRAINT outbox_dropped_ck CHECK ((status = 'DROPPED') = (dropped_reason IS NOT NULL))
);

-- The delivery: per conversation the oldest pending row first; the rows a binding holds; a trigger's rows (paused,
-- resumed, deleted) through its conversations.
CREATE INDEX outbox_pending_idx ON outbox (conversation_id, seq) WHERE status IN ('PENDING', 'HELD');
CREATE INDEX outbox_due_idx ON outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX outbox_binding_idx ON outbox (binding_id);
CREATE INDEX outbox_run_idx ON outbox (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX outbox_decision_idx ON outbox (decision_id) WHERE decision_id IS NOT NULL;
CREATE INDEX outbox_output_idx ON outbox (output_id) WHERE output_id IS NOT NULL;
CREATE INDEX outbox_attachment_idx ON outbox (attachment_id) WHERE attachment_id IS NOT NULL;
CREATE INDEX outbox_message_idx ON outbox (message_id) WHERE message_id IS NOT NULL;
-- The inbox's write-backs dropped in the last 7 days.
CREATE INDEX outbox_dropped_idx ON outbox (company_id, updated_at) WHERE status = 'DROPPED';

-- A row is delivered or dropped once: its outcome never changes afterwards.
CREATE FUNCTION outbox_final_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('DELIVERED', 'DROPPED') AND (NEW.status IS DISTINCT FROM OLD.status
            OR NEW.external_id IS DISTINCT FROM OLD.external_id) THEN
        RAISE EXCEPTION 'A row of the outbox is delivered or dropped once'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_final_ck';
    END IF;
    IF NEW.conversation_id IS DISTINCT FROM OLD.conversation_id OR NEW.binding_id IS DISTINCT FROM OLD.binding_id
       OR NEW.seq IS DISTINCT FROM OLD.seq OR NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.cause_key IS DISTINCT FROM OLD.cause_key OR NEW.body IS DISTINCT FROM OLD.body
       OR NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'What a row of the outbox writes, where and with which binding is fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER outbox_final_ck_trg BEFORE UPDATE ON outbox
    FOR EACH ROW EXECUTE FUNCTION outbox_final_ck();

-- A row is written with its conversation's binding on the place's system (T13): the binding is the conversation's,
-- on the row's system.
CREATE FUNCTION outbox_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM conversation_binding b
             WHERE b.id = NEW.binding_id AND b.company_id = NEW.company_id
               AND b.conversation_id = NEW.conversation_id AND b.system_id = NEW.system_id) THEN
        RAISE EXCEPTION 'A row of the outbox is written with its conversation''s binding on its system'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_binding_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER outbox_binding_ck_trg BEFORE INSERT ON outbox
    FOR EACH ROW EXECUTE FUNCTION outbox_binding_ck();

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY outbox_tenant ON outbox FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Changed in place (its attempts, its status), never deleted: delivered and dropped rows are the conversation's history.
GRANT SELECT, INSERT, UPDATE ON outbox TO ${app_role};

-- ---- cross-company lookup (CONVENTIONS §7, as in V004) -------------------------------------------------------------

-- The delivery (a worker replica) reads the due rows of every company: ids and company ids only, each the oldest
-- undelivered row of its conversation that is pending, unclaimed and due; the server claims each row again in its
-- company's tenant context. The owner gets a SELECT-only policy, which the runtime role never matches.
CREATE POLICY outbox_owner_lookup ON outbox FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION outbox_due(p_now timestamptz, p_limit integer)
    RETURNS TABLE (outbox_id uuid, company_id uuid, conversation_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT o.id, o.company_id, o.conversation_id
      FROM ${flyway:defaultSchema}.outbox o
     WHERE o.status = 'PENDING'
       AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= p_now)
       AND (o.claimed_until IS NULL OR o.claimed_until < p_now)
       AND NOT EXISTS (SELECT 1 FROM ${flyway:defaultSchema}.outbox e
                        WHERE e.conversation_id = o.conversation_id AND e.seq < o.seq
                          AND e.status IN ('PENDING', 'HELD'))
     ORDER BY o.created_at, o.id
     LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION outbox_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox_due(timestamptz, integer) TO ${app_role};
