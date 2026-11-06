-- MCP servers that need no account (ADR-0071; CONVENTIONS §15.2, §15.3, §15.7). The schema owner runs this file.
--
-- An MCP server that checks no credential (one reachable only from Tinfirm's network, behind a NetworkPolicy) still
-- needed an account per principal: a token of any value, which the server ignored, before a conversation or a trigger
-- could use it. An admin now says so on the system, after Tinfirm saw it answer without a credential, and runs call it
-- without one:
--
--   external_system.accounts_required  true unless an MCP server's admin set it false (only an MCP server may)
--   conversation_binding.how           gains OPEN: BOUND on such a system with no account, holder, identity or grant,
--                                      for a personal conversation and, at the system's first use, for a trigger's
--                                      (T5 widens: a trigger's conversation binds FIXED, or OPEN)
--   conversation_rebind                leaves OPEN bindings as they are: they come from no row of the trigger's table
--
-- An OPEN binding keeps how = 'OPEN' and bound_at when it waits or goes (the system set to need accounts again, a
-- suspension, a pause): a personal one that waits for an account binds the person's own or a grant again when they
-- continue, as a binding that never bound does.
--
-- The conventions of V001 apply: the rows are the company's, row-level security and the runtime role's grants stay.

ALTER TABLE external_system
    ADD COLUMN accounts_required boolean NOT NULL DEFAULT true,
    ADD CONSTRAINT external_system_accounts_required_ck CHECK (accounts_required OR kind = 'MCP');

ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_how_ck;
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_how_ck CHECK (
    (how IS NULL OR how = 'OPEN') = (account_id IS NULL)
    AND (how IS NULL OR how IN ('OWN', 'GRANTED', 'CHOSEN', 'FIXED', 'OPEN'))
    AND (how IS NULL OR (how IN ('OWN', 'FIXED', 'OPEN')) = (grant_id IS NULL)));
ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_account_ck;
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_account_ck CHECK (
    (account_id IS NULL AND holder_principal_id IS NULL AND external_id IS NULL AND grant_id IS NULL
     AND (bound_at IS NULL) = (how IS NULL))
    OR (account_id IS NOT NULL AND holder_principal_id IS NOT NULL AND bound_at IS NOT NULL));
ALTER TABLE conversation_binding DROP CONSTRAINT conversation_binding_reason_ck;
ALTER TABLE conversation_binding ADD CONSTRAINT conversation_binding_reason_ck CHECK (
    (state = 'BOUND' AND reason IS NULL AND (account_id IS NOT NULL OR how IS NOT DISTINCT FROM 'OPEN'))
    OR (state = 'WAITING' AND reason IN ('NONE', 'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'CHOOSE', 'PICK_GRANT',
                                         'SUSPENDED', 'SYSTEM_OFF', 'NOT_ENTITLED'))
    OR (state = 'GONE' AND reason IN ('GRANT_REMOVED', 'LEFT_GROUP', 'IDENTITY_CHANGED', 'HOLDER_LEFT',
                                      'SYSTEM_REMOVED', 'ACCOUNT_REMOVED')));

-- ---- conversation_binding_ck: V011's, with OPEN ---------------------------------------------------------------------

-- As V011 says, and: a trigger's conversation binds FIXED or OPEN; OPEN binds only on a system that needs no account
-- (conversation_binding_open_ck), checked when the binding becomes BOUND OPEN, never after.
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

-- ---- conversation_rebind: V011's, leaving OPEN bindings ---------------------------------------------------------------

-- As V011 says, but for a binding OPEN on a system the new table has no row for: it acted with no account, so the new
-- table changes nothing of it, and it stays as it is rather than going ACCOUNT_REMOVED.
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
