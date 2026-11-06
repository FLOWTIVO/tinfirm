-- Package 5, part b: channel triggers, and the trigger checks the webhook's triggers share (the package 5 contract,
-- docs/reviews/2026-10-12-package-5-contract.md §3.9, §3.10, §5.7, §5.12, open point 21).
--
--   trigger.source       gains CHANNEL (a SLACK or TEAMS company system: mentions of the bot in its channels) and
--                        WEBHOOK (a WEBHOOK system: its deliveries, V019's table). A channel trigger's one event is
--                        MENTIONED, without scope, subject or conditions; a webhook's are 1 to 20 DELIVERED, with at
--                        most 5 conditions on the raw body. Neither needs a row on its source system.
--   trigger.waiting_reason gains BOT_MISSING, BOT_RECONNECT, BOT_SUSPENDED (the bot of the trigger's chat system cannot
--                        be used: the trigger waits and goes on by itself once it can, open point 21) and
--                        CHANNEL_UNAVAILABLE (every channel of the trigger is unavailable).
--   trigger_channel      a channel trigger's channels, at most 20 (the service): the agent copied from the trigger,
--                        one trigger per agent per channel and one default per channel among the live triggers (a
--                        trigger's rows go with its soft delete); ACTIVE, or UNAVAILABLE while the bot is out of the
--                        channel or it is archived, deleted or shared, still counting in both indexes.
--   trigger_firing       gains actor_principal_id (the member linked to a chat asker) and DELIVERED (a webhook's).
--   conversation         a trigger's conversation may be held in a chat thread (CHAT_THREAD, kind CHANNEL) or a webhook's
--                        place (WEBHOOK, place_ref {systemId, deliveryId, subjectKey}); a CHANNEL thread's is a
--                        trigger's.
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to the runtime role ${app_role}.

-- ---- trigger -------------------------------------------------------------------------------------------------------

ALTER TABLE trigger DROP CONSTRAINT trigger_source_kind_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_source_kind_ck CHECK (source IN ('TRACKER', 'SCHEDULE', 'CHANNEL',
    'WEBHOOK'));

ALTER TABLE trigger DROP CONSTRAINT trigger_events_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_events_ck CHECK (jsonb_typeof(events) = 'array'
    AND jsonb_array_length(events) <= CASE WHEN source = 'WEBHOOK' THEN 20 ELSE 5 END);

ALTER TABLE trigger DROP CONSTRAINT trigger_shape_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_shape_ck CHECK (
    (source = 'TRACKER' AND system_id IS NOT NULL AND jsonb_array_length(events) BETWEEN 1 AND 5
     AND schedule_cron IS NULL AND schedule_zone IS NULL AND overlap IS NULL AND catch_up IS NULL
     AND next_run_at IS NULL)
    OR (source = 'SCHEDULE' AND system_id IS NULL AND jsonb_array_length(events) = 0 AND scope IS NULL
        AND schedule_cron IS NOT NULL AND schedule_zone IS NOT NULL AND overlap IS NOT NULL
        AND catch_up IS NOT NULL)
    OR (source = 'CHANNEL' AND system_id IS NOT NULL AND jsonb_array_length(events) = 1
        AND events -> 0 ->> 'event' = 'MENTIONED' AND scope IS NULL AND jsonb_array_length(conditions) = 0
        AND subject = 'ANY' AND repeat = 'EVERY_TIME' AND schedule_cron IS NULL AND schedule_zone IS NULL
        AND overlap IS NULL AND catch_up IS NULL AND next_run_at IS NULL)
    OR (source = 'WEBHOOK' AND system_id IS NOT NULL AND jsonb_array_length(events) BETWEEN 1 AND 20
        AND NOT jsonb_path_exists(events, '$[*] ? (@.event != "DELIVERED")') AND scope IS NULL
        AND subject = 'ANY' AND repeat = 'EVERY_TIME' AND schedule_cron IS NULL AND schedule_zone IS NULL
        AND overlap IS NULL AND catch_up IS NULL AND next_run_at IS NULL));

ALTER TABLE trigger DROP CONSTRAINT trigger_waiting_reason_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_waiting_reason_ck CHECK (waiting_reason IS NULL OR waiting_reason IN (
    'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'SUSPENDED', 'SYSTEM_UNREACHABLE', 'SCOPE_UNREADABLE',
    'GATEWAY_NOT_CONFIGURED', 'COMPANY_STOPPED', 'BOT_MISSING', 'BOT_RECONNECT', 'BOT_SUSPENDED',
    'CHANNEL_UNAVAILABLE'));

-- T1 and the trigger's fixed parts, as V011's, and the source's system of each kind.
CREATE OR REPLACE FUNCTION trigger_ck() RETURNS trigger
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
    IF TG_OP = 'INSERT' AND NEW.source = 'CHANNEL' AND NOT EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.owner_principal_id IS NULL
               AND s.kind IN ('SLACK', 'TEAMS')) THEN
        RAISE EXCEPTION 'A channel trigger belongs to a company system of kind SLACK or TEAMS'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_source_ck';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.source = 'WEBHOOK' AND NOT EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.owner_principal_id IS NULL
               AND s.kind = 'WEBHOOK') THEN
        RAISE EXCEPTION 'A webhook trigger belongs to a company system of kind WEBHOOK'
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

-- ---- trigger_channel (contract §3.10) ------------------------------------------------------------------------------

CREATE TABLE trigger_channel (
    company_id  uuid          NOT NULL,
    trigger_id  uuid          NOT NULL,
    system_id   uuid          NOT NULL,
    channel_id  varchar(255)  NOT NULL,
    agent_id    uuid          NOT NULL,
    is_default  boolean       NOT NULL DEFAULT false,
    state       varchar(12)   NOT NULL DEFAULT 'ACTIVE',
    added_at    timestamptz   NOT NULL,
    added_by    uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT trigger_channel_pk PRIMARY KEY (trigger_id, channel_id),
    CONSTRAINT trigger_channel_trigger_fk FOREIGN KEY (trigger_id, agent_id, company_id)
        REFERENCES trigger (id, agent_id, company_id),
    CONSTRAINT trigger_channel_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_channel_id_ck CHECK (length(btrim(channel_id)) BETWEEN 1 AND 255),
    CONSTRAINT trigger_channel_state_ck CHECK (state IN ('ACTIVE', 'UNAVAILABLE'))
);

-- One trigger per agent per channel, one default per channel (CHANNEL_TAKEN), among the live triggers' rows.
CREATE UNIQUE INDEX trigger_channel_agent_uq ON trigger_channel (system_id, channel_id, agent_id);
CREATE UNIQUE INDEX trigger_channel_default_uq ON trigger_channel (system_id, channel_id) WHERE is_default;
CREATE INDEX trigger_channel_company_idx ON trigger_channel (company_id, system_id);

-- A row is of a live channel trigger on its system.
CREATE FUNCTION trigger_channel_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM trigger t
             WHERE t.id = NEW.trigger_id AND t.company_id = NEW.company_id AND t.source = 'CHANNEL'
               AND t.system_id = NEW.system_id AND t.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'A trigger''s channel is of a live channel trigger on its system'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_channel_trigger_ck';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.trigger_id IS DISTINCT FROM OLD.trigger_id
            OR NEW.channel_id IS DISTINCT FROM OLD.channel_id OR NEW.system_id IS DISTINCT FROM OLD.system_id
            OR NEW.agent_id IS DISTINCT FROM OLD.agent_id) THEN
        RAISE EXCEPTION 'A trigger''s channel names its trigger, system, channel and agent for good'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_channel_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trigger_channel_ck_trg BEFORE INSERT OR UPDATE ON trigger_channel
    FOR EACH ROW EXECUTE FUNCTION trigger_channel_ck();

ALTER TABLE trigger_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_channel FORCE ROW LEVEL SECURITY;

CREATE POLICY trigger_channel_tenant ON trigger_channel FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON trigger_channel TO ${app_role};

-- ---- trigger_firing (contract §3.10) -------------------------------------------------------------------------------

ALTER TABLE trigger_firing ADD COLUMN actor_principal_id uuid;
ALTER TABLE trigger_firing ADD CONSTRAINT trigger_firing_actor_principal_fk FOREIGN KEY (actor_principal_id, company_id)
    REFERENCES principal (id, company_id) ON DELETE SET NULL (actor_principal_id);

ALTER TABLE trigger_firing DROP CONSTRAINT trigger_firing_event_ck;
ALTER TABLE trigger_firing ADD CONSTRAINT trigger_firing_event_ck CHECK (event IN ('CREATED', 'UPDATED',
    'STATUS_ENTERED', 'LABEL_ADDED', 'LABEL_REMOVED', 'ASSIGNED', 'MENTIONED', 'COMMENTED', 'CHANGE_REQUEST_OPENED',
    'CHANGE_REQUEST_UPDATED', 'CLOSED', 'SCHEDULE', 'DELIVERED'));

-- ---- conversation (contract §3.9) ----------------------------------------------------------------------------------

ALTER TABLE conversation DROP CONSTRAINT conversation_place_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_place_ck CHECK (place IN ('TINFIRM_THREAD', 'CHAT_THREAD',
    'TRACKER_ISSUE', 'SCHEDULE', 'WEBHOOK'));

-- T4: a personal conversation is paid by its person; a trigger's has its author, the TRIGGER origin and a trigger's
-- place, a chat thread's and a webhook's among them.
ALTER TABLE conversation DROP CONSTRAINT conversation_payer_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_payer_ck CHECK (
    (trigger_id IS NULL AND author_principal_id IS NULL AND person_principal_id IS NOT NULL
     AND payer_principal_id = person_principal_id)
    OR (trigger_id IS NOT NULL AND person_principal_id IS NULL AND author_principal_id IS NOT NULL
        AND origin = 'TRIGGER' AND place IN ('TRACKER_ISSUE', 'SCHEDULE', 'CHAT_THREAD', 'WEBHOOK')));

ALTER TABLE conversation DROP CONSTRAINT conversation_place_ref_trigger_ck;
ALTER TABLE conversation ADD CONSTRAINT conversation_place_ref_trigger_ck CHECK (
    (place <> 'TRACKER_ISSUE' OR (place_ref ? 'subjectId' AND place_ref ? 'systemId' AND place_ref ? 'key'))
    AND (place <> 'SCHEDULE' OR place_ref ? 'triggerId')
    AND (place <> 'WEBHOOK' OR (place_ref ? 'systemId' AND place_ref ? 'deliveryId')));

-- A channel's chat thread is a trigger's: V018's conversation_chat_origin_ck already holds it (a DIRECT_MESSAGE is a
-- DM's, and a chat thread's origin is DIRECT_MESSAGE or TRIGGER).

-- A chat thread's and a webhook's reference never change (C8).
CREATE OR REPLACE FUNCTION conversation_place_fixed_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.place IN ('CHAT_THREAD', 'WEBHOOK') AND NEW.place_ref IS DISTINCT FROM OLD.place_ref THEN
        RAISE EXCEPTION 'A conversation''s chat thread or webhook subject is fixed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_place_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

-- ---- chat_channel (contract §3.4) ----------------------------------------------------------------------------------

-- A Teams channel's serviceUrl, from its last activity: where the bot reads the channel's roster (a trigger's author
-- a member of it, §5.7). NULL on Slack.
ALTER TABLE chat_channel ADD COLUMN service_url varchar(2000);
