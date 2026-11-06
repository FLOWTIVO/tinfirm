-- The Gmail add-on (ADR-0083; CONVENTIONS §7). The schema owner runs this file.
--
-- Google calls the add-on's endpoints for a Google account; the account acts as the person who linked it, through a
-- sign-in link the person confirmed in Tinfirm. The add-on remembers which conversation the person started about which
-- email, so that opening the email again shows it.

-- ---- gmail_link -------------------------------------------------------------------------------------------------------

-- A Google account linked to a person of the company: what the add-on acts as.
--   google_subject   the account's subject in Google's ID tokens (`sub`), which never changes
--   google_email     the account's email as Google asserted it at the link, shown to the person, never matched
--   last_agent_id    the agent the person last chose in the add-on, offered first; checked at each use
--   last_used_at     the last request of the account; a link unused for 30 days ends
CREATE TABLE gmail_link (
    id              uuid         PRIMARY KEY,
    company_id      uuid         NOT NULL REFERENCES company (id),
    user_id         uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    google_subject  varchar(255) NOT NULL,
    google_email    varchar(320),
    last_agent_id   uuid,
    linked_at       timestamptz  NOT NULL,
    last_used_at    timestamptz  NOT NULL,
    CONSTRAINT gmail_link_account_uq UNIQUE (company_id, google_subject),
    CONSTRAINT gmail_link_id_company_uq UNIQUE (id, company_id)
);

CREATE INDEX gmail_link_user_idx ON gmail_link (user_id);

-- ---- gmail_link_request ---------------------------------------------------------------------------------------------

-- A sign-in link offered to one Google account: single use, 10 minutes. The token is 32 random bytes, kept only as its
-- SHA-256 in hex; it travels in the link's fragment and in request bodies, never in a path. An account has at most one
-- unused request: a new one replaces it.
CREATE TABLE gmail_link_request (
    token_hash          varchar(64)  PRIMARY KEY,
    company_id          uuid         NOT NULL REFERENCES company (id),
    google_subject      varchar(255) NOT NULL,
    google_email        varchar(320),
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    consumed_at         timestamptz,
    consumed_by_user_id uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT gmail_link_request_token_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT gmail_link_request_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX gmail_link_request_account_idx ON gmail_link_request (company_id, google_subject);
CREATE INDEX gmail_link_request_expires_idx ON gmail_link_request (expires_at);

-- ---- gmail_email_conversation -----------------------------------------------------------------------------------------

-- The conversation a person started about an email in the add-on: the person's last 100.
--   email_key, thread_key   the first 32 hex digits of the SHA-256 of `gmail:` and Gmail's message id, and of
--                           `gmail-thread:` and its thread id; the ids are not kept
--   action                  SUMMARISE, TRIAGE, INVESTIGATE, DRAFT_REPLY, ASK
CREATE TABLE gmail_email_conversation (
    company_id       uuid        NOT NULL,
    user_id          uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    email_key        varchar(32) NOT NULL,
    thread_key       varchar(32),
    conversation_id  uuid        NOT NULL,
    action           varchar(12) NOT NULL,
    started_at       timestamptz NOT NULL,
    CONSTRAINT gmail_email_conversation_pk PRIMARY KEY (company_id, user_id, email_key),
    CONSTRAINT gmail_email_conversation_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id) ON DELETE CASCADE,
    CONSTRAINT gmail_email_conversation_key_ck CHECK (email_key ~ '^[0-9a-f]{32}$'
        AND (thread_key IS NULL OR thread_key ~ '^[0-9a-f]{32}$')),
    CONSTRAINT gmail_email_conversation_action_ck CHECK (
        action IN ('SUMMARISE', 'TRIAGE', 'INVESTIGATE', 'DRAFT_REPLY', 'ASK'))
);

CREATE INDEX gmail_email_conversation_thread_idx ON gmail_email_conversation (company_id, user_id, thread_key)
    WHERE thread_key IS NOT NULL;
CREATE INDEX gmail_email_conversation_conversation_idx ON gmail_email_conversation (conversation_id);

-- ---- row-level security and grants ----------------------------------------------------------------------------------

ALTER TABLE gmail_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE gmail_link FORCE ROW LEVEL SECURITY;
ALTER TABLE gmail_link_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE gmail_link_request FORCE ROW LEVEL SECURITY;
ALTER TABLE gmail_email_conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE gmail_email_conversation FORCE ROW LEVEL SECURITY;

CREATE POLICY gmail_link_tenant ON gmail_link FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY gmail_link_request_tenant ON gmail_link_request FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());
CREATE POLICY gmail_email_conversation_tenant ON gmail_email_conversation FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON gmail_link TO ${app_role};
-- A request is written once, consumed once, and swept once expired.
GRANT SELECT, INSERT, UPDATE, DELETE ON gmail_link_request TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON gmail_email_conversation TO ${app_role};
