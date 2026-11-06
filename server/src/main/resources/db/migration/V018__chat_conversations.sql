-- Package 5, part a: direct messages as personal conversations (the package 5 contract, §3.9). A chat thread is at
-- most one conversation, for good (C8); a person's own message in their direct message is a PERSON message with the
-- chat's id and author (C3); a decision decided in the chat records how and by which chat user.
--
-- Runs after package 4's V011, which adds message.external_id and external_author, the EXTERNAL kind and
-- decided_by_name, and fixes every conversation's place and origin; this migration adds what is missing and redefines
-- the checks it shares with package 4 as the union of both packages' rules.

-- ---- conversation -----------------------------------------------------------------------------------------------------

-- C8: one conversation per chat thread, open or ended.
CREATE UNIQUE INDEX conversation_chat_thread_uq ON conversation (company_id, (place_ref ->> 'systemId'),
    (place_ref ->> 'channelId'), (place_ref ->> 'threadId')) WHERE place = 'CHAT_THREAD';

-- A CHAT_THREAD conversation's place_ref: {systemId, kind DM | CHANNEL, teamId?, channelId, threadId, serviceUrl?}.
ALTER TABLE conversation ADD CONSTRAINT conversation_place_ref_chat_ck CHECK (
    place <> 'CHAT_THREAD' OR (place_ref ? 'systemId' AND place_ref ? 'channelId' AND place_ref ? 'threadId'
        AND place_ref ->> 'kind' IN ('DM', 'CHANNEL')));

-- A direct message is a chat thread's; a chat thread is a direct message's or (package 5b) a channel trigger's.
ALTER TABLE conversation ADD CONSTRAINT conversation_chat_origin_ck CHECK (
    (origin <> 'DIRECT_MESSAGE' OR (place = 'CHAT_THREAD' AND place_ref ->> 'kind' = 'DM'))
    AND (place <> 'CHAT_THREAD' OR origin IN ('DIRECT_MESSAGE', 'TRIGGER')));

-- A chat thread's reference never changes (C8). Its place and origin are fixed with every conversation's by package 4's
-- conversation_fixed_ck(); this trigger of its own adds the thread, which that one leaves to each place.
CREATE FUNCTION conversation_place_fixed_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.place = 'CHAT_THREAD' AND NEW.place_ref IS DISTINCT FROM OLD.place_ref THEN
        RAISE EXCEPTION 'A conversation''s chat thread is fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_place_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER conversation_place_fixed_ck_trg BEFORE UPDATE ON conversation
    FOR EACH ROW EXECUTE FUNCTION conversation_place_fixed_ck();

-- Why it ended gains SYSTEM_REMOVED (its chat system was removed) and SUPERSEDED (a Teams direct message's next
-- conversation started), beside package 4's reasons.
ALTER TABLE conversation DROP CONSTRAINT conversation_ended_reason_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_ended_reason_ck CHECK (ended_reason IS NULL OR ended_reason IN (
    'USER', 'AGENT_DELETED', 'PERSON_LEFT', 'AUTHOR_LEFT', 'TRIGGER_DELETED', 'SUBJECT_CLOSED', 'SUPERSEDED',
    'SYSTEM_REMOVED'));

-- The member's open direct-message conversations on a system (Teams keeps one open per member per system).
CREATE INDEX conversation_chat_person_idx ON conversation (company_id, person_principal_id, (place_ref ->> 'systemId'))
    WHERE place = 'CHAT_THREAD' AND state = 'OPEN';

-- ---- message ---------------------------------------------------------------------------------------------------------

-- What was said in the chat carries the chat's id of the message (Slack's ts, a Teams activity id) and its author
-- ({id, name, via: SLACK | TEAMS, verified}): a person's own message in their direct message is a PERSON message
-- with both (§3.9); package 4's EXTERNAL message always has both.
ALTER TABLE message ADD COLUMN IF NOT EXISTS external_id varchar(255);
ALTER TABLE message ADD COLUMN IF NOT EXISTS external_author jsonb;

ALTER TABLE message DROP CONSTRAINT message_author_ck;
ALTER TABLE message ADD CONSTRAINT message_author_ck CHECK (
    (kind = 'PERSON' AND author_principal_id IS NOT NULL AND (external_id IS NULL) = (external_author IS NULL)
        AND (external_author IS NULL OR jsonb_typeof(external_author) = 'object'))
    OR (kind = 'EXTERNAL' AND external_id IS NOT NULL AND external_author IS NOT NULL
        AND jsonb_typeof(external_author) = 'object')
    OR (kind IN ('AGENT', 'SYSTEM') AND author_principal_id IS NULL AND external_id IS NULL
        AND external_author IS NULL));

-- C3: a message of the place is stored once per conversation.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'message_external_uq') THEN
        ALTER TABLE message ADD CONSTRAINT message_external_uq UNIQUE (conversation_id, external_id);
    END IF;
END
$$;

-- ---- decision --------------------------------------------------------------------------------------------------------

-- How a decision was decided: in the app (APP) or by a press or an answer in the chat (CHAT), with the chat user who
-- pressed or answered and their name there (package 4's decided_by_name).
ALTER TABLE decision ADD COLUMN IF NOT EXISTS decided_by_name varchar(255);
ALTER TABLE decision ADD COLUMN decided_via varchar(8);
ALTER TABLE decision ADD COLUMN decided_by_external_id varchar(255);
ALTER TABLE decision ADD CONSTRAINT decision_decided_via_ck CHECK (
    (decided_via IS NULL OR (status = 'DECIDED' AND decided_via IN ('APP', 'CHAT')))
    AND (decided_by_external_id IS NULL OR decided_via = 'CHAT'));
