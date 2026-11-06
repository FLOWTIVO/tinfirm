-- Package 5, part b: the chat's results through package 4's write-back outbox (the package 5 contract,
-- docs/reviews/2026-10-12-package-5-contract.md §3.11, §5.9, C12, open point 14). A CHAT_THREAD conversation's rows,
-- personal or a trigger's, are written by its chat system's bot (writer BOT), without a binding; package 4's rows keep
-- theirs (writer BINDING). What a chat row writes: a post (CHAT_POST, Markdown of at most 40 000 characters, split at
-- delivery), an update of a card the outbox posted (CHAT_UPDATE, naming that row), a document (CHAT_FILE) or a reaction
-- to a message taken (CHAT_REACTION, the message's id in value). Tinfirm's record of what the bot posted is a
-- system_write CHAT_MESSAGE.

ALTER TABLE outbox ADD COLUMN writer varchar(8) NOT NULL DEFAULT 'BINDING';
ALTER TABLE outbox ALTER COLUMN binding_id DROP NOT NULL;
ALTER TABLE outbox ADD COLUMN target_outbox_id uuid;
ALTER TABLE outbox ADD CONSTRAINT outbox_target_fk FOREIGN KEY (target_outbox_id, company_id)
    REFERENCES outbox (id, company_id);

-- A row is written by its conversation's binding, or by its place's bot without one.
ALTER TABLE outbox ADD CONSTRAINT outbox_writer_ck CHECK (
    (writer = 'BINDING' AND binding_id IS NOT NULL) OR (writer = 'BOT' AND binding_id IS NULL));

ALTER TABLE outbox DROP CONSTRAINT outbox_kind_ck;
ALTER TABLE outbox ADD CONSTRAINT outbox_kind_ck CHECK (kind IN ('COMMENT', 'TRANSITION', 'LABEL_ADD',
    'LABEL_REMOVE', 'CLOSE', 'ATTACHMENT', 'REMOTE_LINK', 'CHAT_POST', 'CHAT_UPDATE', 'CHAT_FILE', 'CHAT_REACTION'));

ALTER TABLE outbox DROP CONSTRAINT outbox_cause_ck;
ALTER TABLE outbox ADD CONSTRAINT outbox_cause_ck CHECK (cause IN ('RESULT', 'FAILURE', 'QUESTION', 'ANSWER',
    'ON_SUCCESS', 'ON_FAILURE', 'CHANGE_REQUEST', 'RECEIVED', 'APPROVAL', 'DECIDED', 'CONNECT', 'CONNECTED',
    'NOTICE'));

-- Each kind carries what it writes; the chat's kinds are the bot's only, and only an update names another row.
ALTER TABLE outbox DROP CONSTRAINT outbox_shape_ck;
ALTER TABLE outbox ADD CONSTRAINT outbox_shape_ck CHECK (
    (kind = 'COMMENT' AND body IS NOT NULL AND length(body) BETWEEN 1 AND 30000)
    OR (kind IN ('TRANSITION', 'LABEL_ADD', 'LABEL_REMOVE') AND value IS NOT NULL AND length(btrim(value)) >= 1)
    OR kind = 'CLOSE'
    OR kind = 'ATTACHMENT'
    OR (kind = 'REMOTE_LINK' AND url IS NOT NULL)
    OR (kind = 'CHAT_POST' AND body IS NOT NULL AND length(body) BETWEEN 1 AND 40000)
    OR (kind = 'CHAT_UPDATE' AND body IS NOT NULL AND length(body) BETWEEN 1 AND 40000
        AND target_outbox_id IS NOT NULL)
    OR kind = 'CHAT_FILE'
    OR (kind = 'CHAT_REACTION' AND value IS NOT NULL AND length(btrim(value)) >= 1));
ALTER TABLE outbox ADD CONSTRAINT outbox_chat_ck CHECK (
    (kind IN ('CHAT_POST', 'CHAT_UPDATE', 'CHAT_FILE', 'CHAT_REACTION')) = (writer = 'BOT')
    AND (target_outbox_id IS NULL OR kind = 'CHAT_UPDATE'));

CREATE INDEX outbox_target_idx ON outbox (target_outbox_id) WHERE target_outbox_id IS NOT NULL;
-- A paused system's rows, made due when it is resumed; the company's, when its stop is lifted.
CREATE INDEX outbox_system_pending_idx ON outbox (company_id, system_id) WHERE status = 'PENDING';

-- What a row writes, where, with which binding or by which writer, and which row it updates are fixed.
CREATE OR REPLACE FUNCTION outbox_final_ck() RETURNS trigger
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
       OR NEW.writer IS DISTINCT FROM OLD.writer OR NEW.target_outbox_id IS DISTINCT FROM OLD.target_outbox_id
       OR NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'What a row of the outbox writes, where and with which binding is fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

-- A binding's row is written with its conversation's binding on the place's system (T13); a bot's row names no
-- binding, and its conversation is held in a chat thread of the row's system, whose update names a row of its own.
CREATE OR REPLACE FUNCTION outbox_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.writer = 'BOT' THEN
        IF NOT EXISTS (
                SELECT 1 FROM conversation c
                 WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id AND c.place = 'CHAT_THREAD'
                   AND c.place_ref ->> 'systemId' = NEW.system_id::text) THEN
            RAISE EXCEPTION 'A row of the bot is written to its conversation''s chat thread'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_binding_ck';
        END IF;
        IF NEW.target_outbox_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM outbox t
                 WHERE t.id = NEW.target_outbox_id AND t.company_id = NEW.company_id
                   AND t.conversation_id = NEW.conversation_id AND t.kind = 'CHAT_POST' AND t.seq < NEW.seq) THEN
            RAISE EXCEPTION 'An update changes an earlier post of its conversation'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'outbox_binding_ck';
        END IF;
        RETURN NEW;
    END IF;
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

-- What the bot posted, recognised by its ts or activity id.
ALTER TABLE system_write DROP CONSTRAINT system_write_kind_ck;
ALTER TABLE system_write ADD CONSTRAINT system_write_kind_ck CHECK (kind IN ('COMMENT', 'TRANSITION', 'LABEL',
    'CLOSE', 'ATTACHMENT', 'REMOTE_LINK', 'CHANGE_REQUEST', 'PUSH', 'ISSUE', 'CHAT_MESSAGE'));
