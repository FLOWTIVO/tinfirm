-- Package 5, part a: chat systems, their bots, chat users and links, and what arrives from the chats (the package 5
-- contract, §3.1 to §3.8 and §4). Every table carries company_id, forced row-level security on app_company_id(),
-- composite foreign keys with the company, and grants to the runtime role ${app_role}, as V001 does. The tables of
-- channel triggers (trigger_channel, the outbox's writer) are package 5b's (V029, V030).

-- ---- external_system (contract §3.1, §3.2) --------------------------------------------------------------------------

-- A chat system's own secrets, written and never returned (§3.1): Slack's signing secret (Events API) and its
-- app-level token (Socket Mode), each a managed company secret the system keeps (SecretKeeper). The bot's own token,
-- and Teams' app password, are the bot's account's secret (§3.2).
ALTER TABLE external_system ADD COLUMN chat_signing_secret_id uuid;
ALTER TABLE external_system ADD COLUMN chat_app_token_secret_id uuid;
ALTER TABLE external_system ADD CONSTRAINT external_system_chat_signing_secret_fk
    FOREIGN KEY (chat_signing_secret_id, company_id)
    REFERENCES secret (id, company_id) ON DELETE SET NULL (chat_signing_secret_id);
ALTER TABLE external_system ADD CONSTRAINT external_system_chat_app_token_secret_fk
    FOREIGN KEY (chat_app_token_secret_id, company_id)
    REFERENCES secret (id, company_id) ON DELETE SET NULL (chat_app_token_secret_id);
ALTER TABLE external_system ADD CONSTRAINT external_system_chat_secrets_ck CHECK (
    (chat_signing_secret_id IS NULL AND chat_app_token_secret_id IS NULL) OR kind = 'SLACK');
CREATE INDEX external_system_chat_signing_secret_idx ON external_system (chat_signing_secret_id)
    WHERE chat_signing_secret_id IS NOT NULL;
CREATE INDEX external_system_chat_app_token_secret_idx ON external_system (chat_app_token_secret_id)
    WHERE chat_app_token_secret_id IS NOT NULL;

-- One system per Slack workspace or Teams tenant per company (§3.1): the workspace once the bot named it (teamId),
-- the tenant from the start (tenantId).
CREATE UNIQUE INDEX external_system_chat_workspace_uq
    ON external_system (company_id, kind, (coalesce(settings ->> 'teamId', settings ->> 'tenantId')))
    WHERE kind IN ('SLACK', 'TEAMS') AND coalesce(settings ->> 'teamId', settings ->> 'tenantId') IS NOT NULL;

-- An inbound request names its system in its path (C2): its company and kind come from here, never from the
-- payload. The owner reads the table through a SELECT-only policy the runtime role never matches.
CREATE POLICY external_system_owner_lookup ON external_system FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION chat_system_by_id(p_system_id uuid)
    RETURNS TABLE (system_id uuid, company_id uuid, kind varchar)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.id, s.company_id, s.kind
      FROM ${flyway:defaultSchema}.external_system s
     WHERE s.id = p_system_id AND s.kind IN ('SLACK', 'TEAMS')
$$;

REVOKE ALL ON FUNCTION chat_system_by_id(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION chat_system_by_id(uuid) TO ${app_role};

-- The chat systems of every company with a bot, for the Socket Mode connections a worker replica holds and the watch
-- of silent Events API subscriptions: ids and company ids only.
CREATE FUNCTION chat_systems_with_bot()
    RETURNS TABLE (system_id uuid, company_id uuid, kind varchar)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.id, s.company_id, s.kind
      FROM ${flyway:defaultSchema}.external_system s
     WHERE s.kind IN ('SLACK', 'TEAMS') AND s.bot_account_id IS NOT NULL
     ORDER BY s.id
$$;

REVOKE ALL ON FUNCTION chat_systems_with_bot() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION chat_systems_with_bot() TO ${app_role};

-- C9: a bot's account is never lent to a group (BOT_ACCOUNT): the service checks it first (Grants.create), and this is
-- the database's last word.
CREATE FUNCTION account_grant_bot_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM external_system s
                WHERE s.bot_account_id = NEW.account_id AND s.company_id = NEW.company_id) THEN
        RAISE EXCEPTION 'A chat system''s bot account is never granted'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'account_grant_bot_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER account_grant_bot_ck_trg BEFORE INSERT OR UPDATE ON account_grant
    FOR EACH ROW EXECUTE FUNCTION account_grant_bot_ck();

-- ---- app_user (contract §3.8) ----------------------------------------------------------------------------------------

-- The Entra ID tenant and object of the user's last login through an Entra v2.0 issuer
-- (https://login.microsoftonline.com/{tid}/v2.0) whose tid claim is the issuer's tenant, read from the ID token
-- itself; NULL otherwise and for local users. Teams links a chat user automatically by these two only (§5.4).
ALTER TABLE app_user ADD COLUMN oidc_tenant_id varchar(64);
ALTER TABLE app_user ADD COLUMN oidc_object_id varchar(64);
ALTER TABLE app_user ADD CONSTRAINT app_user_oidc_entra_ck CHECK (
    (oidc_tenant_id IS NULL) = (oidc_object_id IS NULL) AND (oidc_tenant_id IS NULL OR auth_source = 'OIDC'));
CREATE UNIQUE INDEX app_user_oidc_entra_uq ON app_user (oidc_tenant_id, oidc_object_id)
    WHERE oidc_tenant_id IS NOT NULL;

-- ---- chat_user (contract §3.3) ---------------------------------------------------------------------------------------

-- What Tinfirm knows of a chat user, for the audience check (§5.3) and to write to them. Every sender has a row, so
-- that a refusal is told once an hour.
--   external_id       Slack's user id; on Teams the user's 29: id, which every activity carries
--   aad_object_id     Teams: the user's Entra object id (aadObjectId), which a link names; NULL on Slack
--   team_id           the user's workspace or tenant
--   audience          MEMBER (served), GUEST, EXTERNAL, BOT, DELETED; NULL until first checked
--   dm_ref            the direct message: Slack's channel; Teams' conversation id and serviceUrl
--   checked_at        when the platform was last asked (users.info, the roster); read again after an hour
--   notice_shown_at   the Owner notice of the first direct message (row 18), once
--   link_offered_at   the last sign-in link offered, at most one per 10 minutes
--   refusal_told_at   the last refusal told, at most once an hour
CREATE TABLE chat_user (
    company_id       uuid         NOT NULL,
    system_id        uuid         NOT NULL,
    external_id      varchar(255) NOT NULL,
    aad_object_id    varchar(64),
    team_id          varchar(255),
    audience         varchar(8),
    display_name     varchar(200),
    dm_ref           jsonb,
    checked_at       timestamptz,
    last_seen_at     timestamptz  NOT NULL,
    notice_shown_at  timestamptz,
    link_offered_at  timestamptz,
    refusal_told_at  timestamptz,
    CONSTRAINT chat_user_pk PRIMARY KEY (system_id, external_id),
    CONSTRAINT chat_user_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_user_audience_ck CHECK (
        audience IS NULL OR audience IN ('MEMBER', 'GUEST', 'EXTERNAL', 'BOT', 'DELETED')),
    CONSTRAINT chat_user_dm_ref_ck CHECK (dm_ref IS NULL OR jsonb_typeof(dm_ref) = 'object')
);

CREATE INDEX chat_user_company_idx ON chat_user (company_id, system_id);
CREATE INDEX chat_user_aad_idx ON chat_user (system_id, aad_object_id) WHERE aad_object_id IS NOT NULL;
CREATE INDEX chat_user_last_seen_idx ON chat_user (last_seen_at);

-- ---- chat_channel (contract §3.4) ------------------------------------------------------------------------------------

-- The channels the bot is in, kept from the platforms' lifecycle events (public and private channels alike).
--   kind       PUBLIC, PRIVATE, SHARED (Slack Connect, a Teams shared channel)
CREATE TABLE chat_channel (
    company_id  uuid         NOT NULL,
    system_id   uuid         NOT NULL,
    channel_id  varchar(255) NOT NULL,
    team_id     varchar(255),
    name        varchar(255),
    kind        varchar(8)   NOT NULL DEFAULT 'PUBLIC',
    bot_member  boolean      NOT NULL DEFAULT true,
    archived    boolean      NOT NULL DEFAULT false,
    deleted     boolean      NOT NULL DEFAULT false,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT chat_channel_pk PRIMARY KEY (system_id, channel_id),
    CONSTRAINT chat_channel_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_channel_kind_ck CHECK (kind IN ('PUBLIC', 'PRIVATE', 'SHARED'))
);

CREATE INDEX chat_channel_company_idx ON chat_channel (company_id, system_id);

-- ---- chat_link_request (contract §3.5, security review row 5) ------------------------------------------------------

-- A sign-in link sent to one chat user: single use, 10 minutes. The token is 32 random bytes, kept only as its
-- SHA-256 in hex; it travels in the link's fragment and in request bodies, never in a path.
--   external_id, display_name, team_id   the chat user it was sent to, as the platform named them then
--   held_event_ids                        the direct messages it holds (at most 5), handled once linked
--   outcome                               LINKED, REFUSED (with the reason in refused_reason)
CREATE TABLE chat_link_request (
    token_hash          varchar(64)  PRIMARY KEY,
    company_id          uuid         NOT NULL,
    system_id           uuid         NOT NULL,
    external_id         varchar(255) NOT NULL,
    display_name        varchar(200),
    team_id             varchar(255),
    team_name           varchar(255),
    held_event_ids      uuid[]       NOT NULL DEFAULT '{}',
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    consumed_at         timestamptz,
    consumed_by_user_id uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    outcome             varchar(8),
    refused_reason      varchar(32),
    CONSTRAINT chat_link_request_token_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chat_link_request_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_link_request_held_ck CHECK (cardinality(held_event_ids) <= 5),
    CONSTRAINT chat_link_request_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT chat_link_request_outcome_ck CHECK (
        (consumed_at IS NULL AND outcome IS NULL AND consumed_by_user_id IS NULL)
        OR (consumed_at IS NOT NULL AND outcome IN ('LINKED', 'REFUSED'))),
    CONSTRAINT chat_link_request_refused_ck CHECK ((outcome = 'REFUSED') = (refused_reason IS NOT NULL))
);

CREATE INDEX chat_link_request_user_idx ON chat_link_request (system_id, external_id, created_at DESC);
CREATE INDEX chat_link_request_expires_idx ON chat_link_request (expires_at) WHERE consumed_at IS NULL;

-- ---- chat_preference (contract §3.6) ---------------------------------------------------------------------------------

-- The agent a member's direct messages go to without a handle (§5.6). An agent the member no longer uses, or that was
-- deleted, counts as none.
CREATE TABLE chat_preference (
    company_id        uuid        NOT NULL,
    principal_id      uuid        PRIMARY KEY,
    principal_kind    varchar(8)  NOT NULL DEFAULT 'MEMBER',
    default_agent_id  uuid,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT chat_preference_principal_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT chat_preference_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE,
    CONSTRAINT chat_preference_agent_fk FOREIGN KEY (default_agent_id, company_id)
        REFERENCES agent (id, company_id)
);

CREATE INDEX chat_preference_agent_idx ON chat_preference (default_agent_id) WHERE default_agent_id IS NOT NULL;

-- ---- chat_event (contract §3.7) --------------------------------------------------------------------------------------

-- What arrived from a chat platform, verified, kept to act once (C3) and in each thread's order.
--   event_id      Slack's event_id; a Slack button {user}:{action_ts}; a Socket Mode envelope's event id; on Teams
--                 {conversation.id}:{activity id} (a Teams activity's id is unique only within its conversation)
--   kind          MESSAGE (a direct message), MENTION (in a channel), ACTION (a button), LIFECYCLE
--   sender        the chat user who sent it (their external id), for the per-user holds
--   thread_key    {channelId}:{threadId}: a thread's events are handled in platform_ts order
--   payload       the normalized event, cleared once handled
--   status        RECEIVED, AWAITING_LINK (held for a sign-in link), AWAITING_AGENT (held for the agent picker),
--                 HANDLED, IGNORED, REFUSED, FAILED, with the reason of §7
--   held_until    when a held event is dropped and its sender told
CREATE TABLE chat_event (
    id              uuid          PRIMARY KEY,
    company_id      uuid          NOT NULL,
    system_id       uuid          NOT NULL,
    event_id        varchar(512)  NOT NULL,
    kind            varchar(10)   NOT NULL,
    sender          varchar(255),
    thread_key      varchar(600)  NOT NULL,
    platform_ts     numeric(20,6) NOT NULL,
    retry_num       integer       NOT NULL DEFAULT 0,
    payload         jsonb,
    status          varchar(16)   NOT NULL DEFAULT 'RECEIVED',
    reason          varchar(32),
    attempts        integer       NOT NULL DEFAULT 0,
    next_attempt_at timestamptz   NOT NULL,
    claimed_until   timestamptz,
    held_until      timestamptz,
    received_at     timestamptz   NOT NULL,
    handled_at      timestamptz,
    CONSTRAINT chat_event_id_company_uq UNIQUE (id, company_id),
    -- C3: an event, a press, an activity acts at most once.
    CONSTRAINT chat_event_uq UNIQUE (system_id, event_id),
    CONSTRAINT chat_event_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_event_kind_ck CHECK (kind IN ('MESSAGE', 'MENTION', 'ACTION', 'LIFECYCLE')),
    CONSTRAINT chat_event_status_ck CHECK (status IN ('RECEIVED', 'AWAITING_LINK', 'AWAITING_AGENT', 'HANDLED',
                                                      'IGNORED', 'REFUSED', 'FAILED')),
    CONSTRAINT chat_event_reason_ck CHECK (reason IS NULL OR reason IN (
        'GUEST', 'EXTERNAL', 'BOT', 'DELETED', 'UNLINKED', 'NOT_YOURS', 'RATE_LIMITED', 'NO_TRIGGER', 'OTHER_AGENT',
        'THREAD_ENDED', 'TRIGGER_PAUSED', 'AGENT_PAUSED', 'NO_BOT', 'NOT_ADDRESSED', 'NO_AGENT_CHOSEN',
        'LINK_EXPIRED', 'NOT_USABLE', 'NOT_SERVED', 'START_REFUSED', 'FAILED', 'EDITED', 'OTHER_TEAM')),
    CONSTRAINT chat_event_payload_ck CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object'),
    CONSTRAINT chat_event_held_ck CHECK (
        (status IN ('AWAITING_LINK', 'AWAITING_AGENT')) = (held_until IS NOT NULL)),
    CONSTRAINT chat_event_handled_ck CHECK (
        (status IN ('RECEIVED', 'AWAITING_LINK', 'AWAITING_AGENT')) = (handled_at IS NULL))
);

CREATE INDEX chat_event_due_idx ON chat_event (thread_key, platform_ts, received_at)
    WHERE status = 'RECEIVED';
CREATE INDEX chat_event_held_idx ON chat_event (system_id, sender, received_at)
    WHERE status IN ('AWAITING_LINK', 'AWAITING_AGENT');
CREATE INDEX chat_event_received_idx ON chat_event (received_at);
CREATE INDEX chat_event_system_idx ON chat_event (company_id, system_id, received_at DESC);

CREATE POLICY chat_event_owner_lookup ON chat_event FOR SELECT TO CURRENT_USER USING (true);

-- The oldest RECEIVED event of each thread whose next attempt is due and which no replica holds, oldest thread first:
-- ids and company ids only; the server claims each row again in its company's tenant context (FOR UPDATE SKIP
-- LOCKED), so one replica handles a thread at a time and in the platform's order.
CREATE FUNCTION chat_event_due(p_now timestamptz, p_limit integer)
    RETURNS TABLE (event_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT d.id, d.company_id
      FROM (SELECT DISTINCT ON (e.system_id, e.thread_key) e.id, e.company_id, e.next_attempt_at, e.claimed_until,
                   e.received_at
              FROM ${flyway:defaultSchema}.chat_event e
             WHERE e.status = 'RECEIVED'
             ORDER BY e.system_id, e.thread_key, e.platform_ts, e.received_at, e.id) d
     WHERE d.next_attempt_at <= p_now AND (d.claimed_until IS NULL OR d.claimed_until < p_now)
     ORDER BY d.received_at
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION chat_event_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION chat_event_due(timestamptz, integer) TO ${app_role};

-- Held events whose hold ran out, and handled rows older than a day, of every company: ids and company ids only.
CREATE FUNCTION chat_event_expired(p_now timestamptz, p_limit integer)
    RETURNS TABLE (event_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT e.id, e.company_id
      FROM ${flyway:defaultSchema}.chat_event e
     WHERE (e.status IN ('AWAITING_LINK', 'AWAITING_AGENT') AND e.held_until <= p_now)
        OR e.received_at < p_now - interval '24 hours'
     ORDER BY e.received_at
     LIMIT least(greatest(p_limit, 1), 1000)
$$;

REVOKE ALL ON FUNCTION chat_event_expired(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION chat_event_expired(timestamptz, integer) TO ${app_role};

-- ---- chat_setting (contract §5.2, open point 11) ---------------------------------------------------------------------

-- The company's chat rates, set by admins; without a row the instance's configuration holds
-- (TINFIRM_CHAT_MESSAGES_PER_USER_MINUTE, _PER_USER_DAY, TINFIRM_CHAT_EVENTS_PER_SYSTEM_MINUTE). The body's size stays
-- the operator's: it protects the server, not a company's budget.
CREATE TABLE chat_setting (
    company_id                 uuid        PRIMARY KEY REFERENCES company (id),
    messages_per_user_minute   integer     NOT NULL,
    messages_per_user_day      integer     NOT NULL,
    events_per_system_minute   integer     NOT NULL,
    updated_at                 timestamptz NOT NULL,
    updated_by                 uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chat_setting_limits_ck CHECK (
        messages_per_user_minute BETWEEN 1 AND 600
        AND messages_per_user_day BETWEEN 1 AND 10000
        AND events_per_system_minute BETWEEN 1 AND 6000)
);

-- ---- chat_notice (contract §5.2, §5.4, the conversation module's InboxItems) ----------------------------------------

-- The chat module's items of the inbox: to a member (principal_id), or to the company's admins (principal_id NULL).
--   kind      LINKED: a chat account was linked to the member ("Not you? Unlink"), 7 days;
--             SUBSCRIPTION_SILENT: a Slack system's event subscription seems disabled (admins), until an event arrives
--   details   ids and the chat user's ids and workspace; never a message
CREATE TABLE chat_notice (
    id            uuid        PRIMARY KEY,
    company_id    uuid        NOT NULL,
    system_id     uuid        NOT NULL,
    principal_id  uuid,
    kind          varchar(24) NOT NULL,
    details       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL,
    expires_at    timestamptz,
    CONSTRAINT chat_notice_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT chat_notice_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_notice_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_notice_kind_ck CHECK (kind IN ('LINKED', 'SUBSCRIPTION_SILENT')),
    CONSTRAINT chat_notice_audience_ck CHECK ((kind = 'LINKED') = (principal_id IS NOT NULL)),
    CONSTRAINT chat_notice_details_ck CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX chat_notice_principal_idx ON chat_notice (company_id, principal_id, created_at);
CREATE UNIQUE INDEX chat_notice_silent_uq ON chat_notice (system_id) WHERE kind = 'SUBSCRIPTION_SILENT';

-- ---- chat_post (the minimal writer of package 5a) --------------------------------------------------------------------

-- What the bot posted for a conversation, by its id at the platform (Slack's ts, a Teams activity id): a card is
-- updated once its decision is decided, and a press counts only on the card the bot posted for that decision (C10).
-- Package 5b replaces it with the outbox's rows (writer BOT) and system_write CHAT_MESSAGE.
--   purpose   ANSWER, FAILURE, QUESTION, APPROVAL, CONNECT, NOTICE, PICKER, LINK, SYSTEM
CREATE TABLE chat_post (
    id               uuid         PRIMARY KEY,
    company_id       uuid         NOT NULL,
    system_id        uuid         NOT NULL,
    conversation_id  uuid,
    decision_id      uuid,
    binding_id       uuid,
    purpose          varchar(10)  NOT NULL,
    channel_id       varchar(255) NOT NULL,
    thread_id        varchar(255),
    external_id      varchar(255) NOT NULL,
    created_at       timestamptz  NOT NULL,
    CONSTRAINT chat_post_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_post_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT chat_post_decision_fk FOREIGN KEY (decision_id, company_id)
        REFERENCES decision (id, company_id),
    CONSTRAINT chat_post_purpose_ck CHECK (purpose IN ('ANSWER', 'FAILURE', 'QUESTION', 'APPROVAL', 'CONNECT',
                                                       'NOTICE', 'PICKER', 'LINK', 'SYSTEM'))
);

CREATE INDEX chat_post_decision_idx ON chat_post (decision_id) WHERE decision_id IS NOT NULL;
CREATE INDEX chat_post_binding_idx ON chat_post (binding_id) WHERE binding_id IS NOT NULL;
CREATE INDEX chat_post_conversation_idx ON chat_post (conversation_id, created_at) WHERE conversation_id IS NOT NULL;
CREATE INDEX chat_post_external_idx ON chat_post (system_id, external_id);

-- ---- chat_socket (contract §5.1, §5.2: Socket Mode) ---------------------------------------------------------------

-- The Socket Mode connections of a Slack system, one row per slot (at most tinfirm.chat.sockets-per-system): the
-- worker replica that holds it (holder, its own random id) until lease_until, renewed while its connection lives;
-- another replica takes a slot whose lease ran out.
CREATE TABLE chat_socket (
    company_id   uuid        NOT NULL,
    system_id    uuid        NOT NULL,
    slot         integer     NOT NULL,
    holder       varchar(64) NOT NULL,
    lease_until  timestamptz NOT NULL,
    CONSTRAINT chat_socket_pk PRIMARY KEY (system_id, slot),
    CONSTRAINT chat_socket_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT chat_socket_slot_ck CHECK (slot BETWEEN 0 AND 9)
);

-- ---- row-level security and grants ----------------------------------------------------------------------------------

ALTER TABLE chat_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_user FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_channel FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_link_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_link_request FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_preference FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_event FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_setting FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_notice FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_post ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_post FORCE ROW LEVEL SECURITY;
ALTER TABLE chat_socket ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_socket FORCE ROW LEVEL SECURITY;

CREATE POLICY chat_user_tenant ON chat_user FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_channel_tenant ON chat_channel FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_link_request_tenant ON chat_link_request FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_preference_tenant ON chat_preference FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_event_tenant ON chat_event FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_setting_tenant ON chat_setting FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_notice_tenant ON chat_notice FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_post_tenant ON chat_post FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY chat_socket_tenant ON chat_socket FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON chat_user TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_channel TO ${app_role};
-- A request is written once, consumed once, and swept once expired.
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_link_request TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_preference TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_event TO ${app_role};
-- Changed in place, never removed.
GRANT SELECT, INSERT, UPDATE ON chat_setting TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_notice TO ${app_role};
GRANT SELECT, INSERT, DELETE ON chat_post TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON chat_socket TO ${app_role};
