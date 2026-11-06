-- A trigger follows its service account's credential (ADR-0072, open point 3 as decided again). The schema owner runs
-- this file.
--
-- Until now a trigger never switched credentials: an admin who lent a service account another credential on a system,
-- in place of the one it borrowed, paused every trigger that acted through it (NOT_ENTITLED), and its open
-- conversations waited for good. The trigger follows the service account now: its row names the credential lent in
-- the lending's transaction, its open conversations bind it at their next check, and its author is told. A credential
-- withdrawn or disabled still pauses it.
--
--   conversation_binding  a trigger's binding through a service account may name, in place of its credential, the one
--                         the service account borrows now on the system, with that one's identity
--   trigger_account       a live trigger's row whose service account borrows another credential now names that one
--                         (the triggers paused since V049 for it are resumed by their authors, as before)
--
-- The conventions of V001 apply: the rows are the company's, row-level security and the runtime role's grants stay.

-- ---- conversation_binding: a trigger's binding follows its service account ------------------------------------------

-- As V049 says, but for a trigger's binding through a service account, which may follow it: bound to the credential the
-- service account borrows now, with its identity, the holder and the binding's kind unchanged.
CREATE OR REPLACE FUNCTION conversation_binding_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person  uuid;
    v_author  uuid;
    v_trigger uuid;
    v_rebind  boolean;
    v_follow  boolean;
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
        -- A trigger's binding through a service account, bound to another credential that the service account
        -- borrows on the system now: the check below holds it to that lending.
        v_follow := v_trigger IS NOT NULL AND OLD.how = 'FIXED' AND NEW.how = 'FIXED'
            AND OLD.account_id IS NOT NULL AND NEW.account_id IS DISTINCT FROM OLD.account_id
            AND NEW.holder_principal_id IS NOT DISTINCT FROM OLD.holder_principal_id
            AND EXISTS (
                SELECT 1 FROM service_account_credential l
                 WHERE l.principal_id = NEW.holder_principal_id AND l.company_id = NEW.company_id
                   AND l.system_id = NEW.system_id AND l.account_id = NEW.account_id);
        IF OLD.account_id IS NOT NULL AND NOT v_rebind AND NOT v_follow THEN
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

-- ---- trigger_account: a row names what its service account borrows now ----------------------------------------------

-- The schema owner bypasses row-level security for this step only, as in V033: on trigger_account, which it changes,
-- and on the tables the checks of a changed row read (trigger_account_ck, trigger_table_ck). The deferred check runs
-- before the security is forced again, which ALTER TABLE needs. A chat system's bot acts for no trigger (C9), and a
-- tracker trigger's source row keeps a credential without an identity out (T2): those triggers stay as they were.
ALTER TABLE trigger_account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger NO FORCE ROW LEVEL SECURITY;
ALTER TABLE principal NO FORCE ROW LEVEL SECURITY;
ALTER TABLE account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE service_account_credential NO FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_system NO FORCE ROW LEVEL SECURITY;
ALTER TABLE external_system NO FORCE ROW LEVEL SECURITY;

UPDATE trigger_account r
   SET account_id = l.account_id, external_id = c.external_id
  FROM trigger t, account old, service_account_credential l, account c
 WHERE t.id = r.trigger_id AND t.company_id = r.company_id AND t.deleted_at IS NULL
   AND old.id = r.account_id AND old.principal_id IS NULL
   AND l.principal_id = r.holder_principal_id AND l.company_id = r.company_id AND l.system_id = r.system_id
   AND l.account_id <> r.account_id
   AND c.id = l.account_id
   AND NOT (t.source = 'TRACKER' AND t.system_id = r.system_id AND c.external_id IS NULL)
   AND NOT EXISTS (SELECT 1 FROM external_system s WHERE s.bot_account_id = l.account_id);

SET CONSTRAINTS trigger_account_table_ck_trg IMMEDIATE;
SET CONSTRAINTS trigger_account_table_ck_trg DEFERRED;

ALTER TABLE external_system FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_system FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE service_account_credential FORCE ROW LEVEL SECURITY;
ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE principal FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_account FORCE ROW LEVEL SECURITY;
