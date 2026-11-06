-- A company credential belongs to its system, lent to groups and to service accounts (ADR-0072; CONVENTIONS §15.3).
-- The schema owner runs this file.
--
-- Until now every credential was a principal's account: a member's own, or a service identity's, which a grant lent to a
-- group and a trigger's row named. A company credential now belongs to its system: an account the system holds, with
-- no principal (principal_kind SYSTEM) and a name, several on one system. A grant lends it to a group; a service
-- account (as service identities are now called) borrows at most one per system. Members' own accounts do not change.
--
--   account                        principal_id NULL and principal_kind 'SYSTEM' for a credential, with its name
--                                  (unique on its system ignoring case) and whether it is enabled; never on a personal
--                                  server (account_ck); I2 becomes: an identity is members' own accounts' or the
--                                  system's credentials', never both, and two credentials may share one
--   service_account_credential     the credential a service account borrows on a system, READ or WRITE
--   account_grant                  lends a credential (account_principal_kind 'SYSTEM')
--   external_system.bot_account_id a chat system's bot: a credential on it, which a service account borrows
--   conversation_binding           a binding through a grant has no holder; a trigger's binds its author's own account,
--                                  or the credential its service account borrows
--   trigger_account                a row names its author's own account, or a service account and its credential
--   oauth_authorization_request    a sign-in is for a member's own account, or for a credential: a new one by its
--                                  name, or one to sign in again
--   trigger_poll                   a credential's poll has no holder: it serves every service account's triggers
--   conversation_rebind            binds again a row whose service account changed with the same credential
--
-- Every service identity's account becomes a credential on its system, named after the service identity and lent to
-- it WRITE; its grants, the triggers' rows, the conversations' bindings and a chat system's bot keep naming it. A
-- binding through a grant loses its holder. Pending sign-ins are dropped: they expire in ten minutes.
--
-- The conventions of V001 apply: the rows are the company's, row-level security and the runtime role's grants stay.
-- The schema owner bypasses row-level security for the data steps only, as V002 and V033 do.

-- ---- account: a credential is an account its system holds -----------------------------------------------------------

ALTER TABLE account
    ALTER COLUMN principal_id DROP NOT NULL,
    ADD COLUMN name varchar(100),
    ADD COLUMN enabled boolean NOT NULL DEFAULT true;

-- The foreign keys that say the lent account's principal kind go while the rows change kind; they come back on SYSTEM.
ALTER TABLE account_grant DROP CONSTRAINT account_grant_service_fk, DROP CONSTRAINT account_grant_service_ck;
ALTER TABLE external_system DROP CONSTRAINT external_system_bot_fk, DROP CONSTRAINT external_system_bot_kind_ck;

-- I6 and I3 as V001 says them, a credential on a company system only; and I2 between members and credentials, which a
-- unique index cannot say: an identity on a system is members' own accounts' or the system's credentials', never both.
-- The members' own stay unique by account_identity_uq; credentials may share one (two tokens of one bot user). A
-- transaction-scoped advisory lock per identity serialises two connects of it, so that the check cannot race.
CREATE OR REPLACE FUNCTION account_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_owner        uuid;
    v_user         uuid;
    v_secret_owner uuid;
BEGIN
    SELECT owner_principal_id INTO v_owner
      FROM external_system WHERE id = NEW.system_id AND company_id = NEW.company_id;
    IF v_owner IS NOT NULL AND NEW.principal_id IS DISTINCT FROM v_owner THEN
        RAISE EXCEPTION 'Only its owner holds an account on a personal system, and it holds no credential'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'account_personal_ck';
    END IF;
    IF NEW.secret_id IS NOT NULL THEN
        SELECT owner_user_id INTO v_secret_owner FROM secret WHERE id = NEW.secret_id AND company_id = NEW.company_id;
        IF NEW.principal_id IS NOT NULL THEN
            SELECT user_id INTO v_user FROM principal WHERE id = NEW.principal_id AND company_id = NEW.company_id;
        END IF;
        IF v_secret_owner IS DISTINCT FROM v_user THEN
            RAISE EXCEPTION 'A member''s account keeps its token in a secret the member owns, a credential in a '
                            'company secret'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'account_secret_owner_ck';
        END IF;
    END IF;
    IF NEW.external_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.external_id IS DISTINCT FROM OLD.external_id
                                        OR NEW.principal_id IS DISTINCT FROM OLD.principal_id) THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('account_identity:' || NEW.system_id::text || ':'
                                                       || NEW.external_id, 0));
        IF EXISTS (SELECT 1 FROM account a
                    WHERE a.system_id = NEW.system_id AND a.external_id = NEW.external_id AND a.id <> NEW.id
                      AND (a.principal_id IS NULL) <> (NEW.principal_id IS NULL)) THEN
            RAISE EXCEPTION 'An identity on a system is members'' own accounts'' or the system''s credentials'', '
                            'never both'
                USING ERRCODE = 'unique_violation', CONSTRAINT = 'account_identity_uq';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---- The data: every service identity's account becomes a credential lent to it --------------------------------------

ALTER TABLE account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE account_grant NO FORCE ROW LEVEL SECURITY;
ALTER TABLE external_system NO FORCE ROW LEVEL SECURITY;
ALTER TABLE principal NO FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_binding NO FORCE ROW LEVEL SECURITY;

CREATE TEMPORARY TABLE v049_lent ON COMMIT DROP AS
SELECT a.id AS account_id, a.company_id, a.system_id, a.principal_id, a.connected_at
  FROM account a
 WHERE a.principal_kind = 'SERVICE';

UPDATE account a
   SET principal_id = NULL, principal_kind = 'SYSTEM', name = left(btrim(p.name), 100), updated_at = now()
  FROM principal p
 WHERE a.principal_kind = 'SERVICE' AND p.id = a.principal_id AND p.company_id = a.company_id;

UPDATE account_grant SET account_principal_kind = 'SYSTEM';
UPDATE external_system SET bot_principal_kind = 'SYSTEM';

-- A binding through a grant has no holder: the credential is the company's, lent to the person's group.
ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_account_ck;
ALTER TABLE conversation_binding DISABLE TRIGGER conversation_binding_ck_trg;
UPDATE conversation_binding SET holder_principal_id = NULL WHERE grant_id IS NOT NULL;
ALTER TABLE conversation_binding ENABLE TRIGGER conversation_binding_ck_trg;

-- ---- account's rules over the new rows ---------------------------------------------------------------------------------

ALTER TABLE account
    ADD CONSTRAINT account_holder_ck CHECK (
        principal_kind IN ('MEMBER', 'SYSTEM')
        AND (principal_kind = 'SYSTEM') = (principal_id IS NULL)
        AND (principal_kind = 'SYSTEM') = (name IS NOT NULL)
        AND (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 100)
        AND (enabled OR principal_kind = 'SYSTEM')
        AND (principal_kind <> 'SYSTEM' OR kind IN ('TOKEN', 'OAUTH')));

-- A credential's name is unique on its system, ignoring case.
CREATE UNIQUE INDEX account_name_uq ON account (system_id, lower(name)) WHERE name IS NOT NULL;

-- I2 among members' own accounts; between members and credentials, account_ck.
DROP INDEX account_identity_uq;
CREATE UNIQUE INDEX account_identity_uq ON account (system_id, external_id)
    WHERE external_id IS NOT NULL AND principal_id IS NOT NULL;
CREATE INDEX account_identity_idx ON account (system_id, external_id) WHERE external_id IS NOT NULL;

-- I5: a grant lends a credential.
ALTER TABLE account_grant
    ALTER COLUMN account_principal_kind SET DEFAULT 'SYSTEM',
    ADD CONSTRAINT account_grant_credential_ck CHECK (account_principal_kind = 'SYSTEM'),
    ADD CONSTRAINT account_grant_credential_fk FOREIGN KEY (account_id, company_id, account_principal_kind)
        REFERENCES account (id, company_id, principal_kind) ON DELETE CASCADE;

-- I9: a chat system's bot is a credential on that system (a service account borrows it, service_account_credential).
ALTER TABLE external_system
    ALTER COLUMN bot_principal_kind SET DEFAULT 'SYSTEM',
    ADD CONSTRAINT external_system_bot_kind_ck CHECK (bot_principal_kind = 'SYSTEM'),
    ADD CONSTRAINT external_system_bot_fk FOREIGN KEY (bot_account_id, id, company_id, bot_principal_kind)
        REFERENCES account (id, system_id, company_id, principal_kind) ON DELETE SET NULL (bot_account_id);

-- ---- service_account_credential: the credential a service account borrows on a system --------------------------------

-- At most one per service account and system, so that a trigger's "Use the accounts of" names one per system; a
-- credential may be lent to several. It goes with its service account and with its credential.
CREATE TABLE service_account_credential (
    company_id             uuid        NOT NULL,
    principal_id           uuid        NOT NULL,
    principal_kind         varchar(8)  NOT NULL DEFAULT 'SERVICE',
    system_id              uuid        NOT NULL,
    account_id             uuid        NOT NULL,
    account_principal_kind varchar(8)  NOT NULL DEFAULT 'SYSTEM',
    access                 varchar(8)  NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    created_by             uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT service_account_credential_pk PRIMARY KEY (principal_id, system_id),
    CONSTRAINT service_account_credential_service_ck CHECK (principal_kind = 'SERVICE'),
    CONSTRAINT service_account_credential_system_ck CHECK (account_principal_kind = 'SYSTEM'),
    CONSTRAINT service_account_credential_access_ck CHECK (access IN ('READ', 'WRITE')),
    CONSTRAINT service_account_credential_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE,
    CONSTRAINT service_account_credential_account_fk
        FOREIGN KEY (account_id, system_id, company_id, account_principal_kind)
        REFERENCES account (id, system_id, company_id, principal_kind) ON DELETE CASCADE
);

CREATE INDEX service_account_credential_account_idx ON service_account_credential (account_id);
CREATE INDEX service_account_credential_system_idx ON service_account_credential (company_id, system_id);

INSERT INTO service_account_credential (company_id, principal_id, system_id, account_id, access, created_at,
                                        updated_at)
SELECT company_id, principal_id, system_id, account_id, 'WRITE', connected_at, now() FROM v049_lent;

ALTER TABLE service_account_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_account_credential FORCE ROW LEVEL SECURITY;

CREATE POLICY service_account_credential_tenant ON service_account_credential FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A lending's access changes in place; lending another credential on the system replaces the row.
GRANT SELECT, INSERT, UPDATE, DELETE ON service_account_credential TO ${app_role};

-- A credential's poll serves the triggers of every service account that borrows the credential: it has no holder, so
-- that deleting one of them leaves it; a member's own account's poll keeps its member.
ALTER TABLE trigger_poll NO FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_poll ALTER COLUMN holder_principal_id DROP NOT NULL;
UPDATE trigger_poll p
   SET holder_principal_id = NULL, updated_at = now()
  FROM account a
 WHERE a.id = p.account_id AND a.principal_id IS NULL;
ALTER TABLE trigger_poll FORCE ROW LEVEL SECURITY;

ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE account_grant FORCE ROW LEVEL SECURITY;
ALTER TABLE external_system FORCE ROW LEVEL SECURITY;
ALTER TABLE principal FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_binding FORCE ROW LEVEL SECURITY;

-- ---- conversation_binding: through a grant without a holder ----------------------------------------------------------

-- A bound account has a holder exactly when no grant lends it: one's own account and a trigger's row have one (the
-- member, the author or the service account that borrows the credential), a grant's credential none.
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_account_ck CHECK (
    (account_id IS NULL AND holder_principal_id IS NULL AND external_id IS NULL AND grant_id IS NULL
     AND (bound_at IS NULL) = (how IS NULL))
    OR (account_id IS NOT NULL AND (holder_principal_id IS NULL) = (grant_id IS NOT NULL) AND bound_at IS NOT NULL));

-- As V048 says, with ADR-0072's accounts: a binding names an account on its system with its identity; a trigger's,
-- its author's own or the credential its service account borrows; a personal one, the person's own, or a credential a
-- grant lends, without a holder.
CREATE OR REPLACE FUNCTION conversation_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person  uuid;
    v_author  uuid;
    v_trigger uuid;
    v_rebind  boolean;
    v_holder  uuid;
BEGIN
    SELECT c.person_principal_id, c.author_principal_id, c.trigger_id INTO v_person, v_author, v_trigger
      FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
    v_rebind := v_trigger IS NOT NULL AND conversation_rebinding(NEW.conversation_id);
    IF (v_trigger IS NOT NULL AND (NEW.how IS NULL OR NEW.how NOT IN ('FIXED', 'OPEN')))
       OR (v_trigger IS NULL AND NEW.how = 'FIXED') THEN
        RAISE EXCEPTION 'A trigger''s conversation binds FIXED, or OPEN without an account; a personal one never FIXED'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_fixed_how_ck';
    END IF;
    -- OPEN only on a system that needs no account, when it binds so: a binding that stays OPEN while an admin sets
    -- the system to need accounts again stops at its next use (the server's check), and is not refused here.
    IF NEW.how = 'OPEN' AND (TG_OP = 'INSERT' OR OLD.how IS DISTINCT FROM 'OPEN' OR OLD.state <> 'BOUND')
       AND NEW.state = 'BOUND' AND EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.accounts_required) THEN
        RAISE EXCEPTION 'A binding is OPEN only on a system that needs no account'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_open_ck';
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
            -- Only one's own account is replaced, by the holder's own on the same system with the same identity; a
            -- credential is signed in again in place.
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
        SELECT a.principal_id INTO v_holder
          FROM account a
         WHERE a.id = NEW.account_id AND a.company_id = NEW.company_id AND a.system_id = NEW.system_id
           AND a.external_id IS NOT DISTINCT FROM NEW.external_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'A binding names an account on its system, with its identity'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
        END IF;
        IF v_trigger IS NOT NULL THEN
            -- A trigger's conversation: its author's own account, or the credential a service account borrows.
            IF NEW.grant_id IS NOT NULL OR NOT (
                   (v_holder IS NOT NULL AND v_holder = NEW.holder_principal_id AND NEW.holder_principal_id = v_author)
                   OR (v_holder IS NULL AND EXISTS (
                        SELECT 1 FROM service_account_credential l
                         WHERE l.principal_id = NEW.holder_principal_id AND l.company_id = NEW.company_id
                           AND l.system_id = NEW.system_id AND l.account_id = NEW.account_id))) THEN
                RAISE EXCEPTION 'A trigger''s conversation binds its author''s own account or the credential a service '
                                'account borrows'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
            END IF;
        ELSIF NEW.grant_id IS NULL THEN
            IF v_holder IS DISTINCT FROM v_person OR NEW.holder_principal_id IS DISTINCT FROM v_person THEN
                RAISE EXCEPTION 'A conversation binds its person''s own account, or a credential a grant lends'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
            END IF;
        ELSIF v_holder IS NOT NULL OR NEW.holder_principal_id IS NOT NULL OR NOT EXISTS (
                SELECT 1 FROM account_grant g
                 WHERE g.id = NEW.grant_id AND g.company_id = NEW.company_id AND g.account_id = NEW.account_id) THEN
            -- I8: never another member's account; a grant lends a credential, which has no holder.
            RAISE EXCEPTION 'A conversation binds its person''s own account or a credential a grant lends, never another '
                            'member''s'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_binding_holder_ck';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---- trigger_account: its author's own account, or a service account and the credential it borrows ------------------

CREATE OR REPLACE FUNCTION trigger_account_ck() RETURNS trigger
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
        RAISE EXCEPTION 'A trigger acts with its author''s own accounts or a service account''s, never another member''s'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'trigger_account_holder_ck';
    END IF;
    IF NEW.account_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM account a
             WHERE a.id = NEW.account_id AND a.company_id = NEW.company_id AND a.system_id = NEW.system_id
               AND (a.principal_id = NEW.holder_principal_id
                    OR (a.principal_id IS NULL AND EXISTS (
                        SELECT 1 FROM service_account_credential l
                         WHERE l.principal_id = NEW.holder_principal_id AND l.company_id = NEW.company_id
                           AND l.system_id = NEW.system_id AND l.account_id = NEW.account_id)))) THEN
        RAISE EXCEPTION 'A row names its holder''s own account, or the credential its service account borrows, on its '
                        'system'
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

-- ---- oauth_authorization_request: a sign-in for a credential --------------------------------------------------------

DELETE FROM oauth_authorization_request;

--   principal_id     the member whose own account the grant becomes; null for a credential
--   credential_id    the credential signed in again; null otherwise
--   credential_name  the name of a new credential the grant becomes; null otherwise
ALTER TABLE oauth_authorization_request
    ALTER COLUMN principal_id DROP NOT NULL,
    ADD COLUMN credential_id uuid,
    ADD COLUMN credential_name varchar(100),
    ADD CONSTRAINT oauth_authorization_request_subject_ck CHECK (
        (principal_id IS NOT NULL AND credential_id IS NULL AND credential_name IS NULL)
        OR (principal_id IS NULL AND (credential_id IS NULL) <> (credential_name IS NULL))),
    ADD CONSTRAINT oauth_authorization_request_credential_fk FOREIGN KEY (credential_id)
        REFERENCES account (id) ON DELETE CASCADE;

CREATE INDEX oauth_authorization_request_credential_idx ON oauth_authorization_request (credential_id)
    WHERE credential_id IS NOT NULL;

-- ---- conversation_rebind: a row whose service account changed, with the same credential -------------------------------

-- As V048 says it; a binding is bound again also when only its holder changed: a reassigned trigger's row may name
-- another service account that borrows the same credential. It bypasses row-level security, so it takes a conversation
-- of the company the caller's tenant context names only.
CREATE OR REPLACE FUNCTION conversation_rebind(p_conversation_id uuid) RETURNS jsonb
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
     WHERE c.id = p_conversation_id AND c.company_id = app_company_id()
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
        SELECT b.id, b.system_id, b.state, b.how, b.account_id, b.holder_principal_id, b.grant_id
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
            IF v_binding.account_id IS DISTINCT FROM v_row.account_id
               OR v_binding.holder_principal_id IS DISTINCT FROM v_row.holder_principal_id
               OR v_binding.state <> 'BOUND' THEN
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
        ELSIF v_binding.state <> 'GONE' AND v_binding.how IS DISTINCT FROM 'OPEN' THEN
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
